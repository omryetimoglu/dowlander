package com.evasi.downloader.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.UUID

internal class GalleryStore(context: Context, private val recovery: Recovery = processRecovery) {
    private val resolver = context.contentResolver
    private val receipts = context.getSharedPreferences("gallery_receipts", Context.MODE_PRIVATE)
    private val pending = context.getSharedPreferences("gallery_pending", Context.MODE_PRIVATE)

    // The first save/recovery takes one snapshot per process. Later journal entries belong
    // to live writes and must not be picked up by another repository instance's recovery.
    internal class Recovery {
        var backlog: LinkedHashSet<String>? = null
        var retryAt = 0L
    }

    private companion object {
        val processRecovery = Recovery()
        const val retryDelayMs = 5_000L
    }

    fun recover() = synchronized(recovery) {
        val backlog = recovery.backlog ?: pending.all.keys.toCollection(linkedSetOf()).also { recovery.backlog = it }
        if (backlog.isEmpty() || SystemClock.elapsedRealtime() < recovery.retryAt) return@synchronized
        val completed = pending.edit()
        val rows = backlog.iterator()
        while (rows.hasNext()) {
            val key = rows.next()
            val finished = try {
                recoverRow(key)
            } catch (_: RuntimeException) {
                false // Provider unavailable; keep the journal entry for a later call.
            }
            if (finished) {
                completed.remove(key)
                rows.remove()
            }
        }
        completed.apply()
        if (backlog.isNotEmpty()) recovery.retryAt = SystemClock.elapsedRealtime() + retryDelayMs
    }

    private fun recoverRow(key: String): Boolean {
        val row = Uri.parse(key)
        val isPending = resolver.query(row, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            if (!it.moveToFirst()) return true // Row already gone.
            it.getInt(0) == 1
        } ?: return false
        // true is the existing write journal format. A published row can survive a
        // crash between MediaStore publication and the removal of that journal entry.
        if (!isPending && pending.getBoolean(key, true)) return true
        return resolver.delete(row, null, null) > 0
    }

    fun discard(row: Uri) = synchronized(recovery) {
        val key = row.toString()
        val deleted = try { resolver.delete(row, null, null) > 0 } catch (_: RuntimeException) { false }
        if (deleted) {
            pending.edit().remove(key).apply()
            recovery.backlog?.remove(key)
        } else {
            // Cancellation may win just after publication, before the caller receives the result.
            // Only this explicit rollback is allowed to delete such an unclaimed published row.
            pending.edit().putBoolean(key, false).apply()
            recovery.backlog?.add(key)
        }
    }

    fun write(
        input: InputStream, declared: String?, name: String?, total: Long?, readFailure: FailureReason,
        kind: MediaKind?, job: Job, onInsert: (Uri) -> Unit, onProgress: (Long, Long?) -> Unit,
        album: String = DEFAULT_ALBUM, outputKey: String? = null,
    ): Uri {
        job.ensureActive()
        val stream = input.buffered(64 * 1024)
        val format = try {
            MediaFormat.inspect(stream, declared)
        } catch (error: IOException) {
            throw sourceFailure(readFailure, error)
        }
        if ((kind == MediaKind.VIDEO && !format.isVideo) || (kind == MediaKind.PHOTO && format.isVideo) ||
            (kind == MediaKind.GIF && !format.isVideo && format.extension !in setOf("gif", "webp", "avif"))) {
            throw MediaFailure(FailureReason.INVALID)
        }
        val row = insert(format, name, normalizeAlbum(album))
        onInsert(row)
        if (!pending.edit().putBoolean(row.toString(), true).commit()) throw MediaFailure(FailureReason.STORAGE)
        if (outputKey != null && !receipts.edit().putString(outputKey, row.toString()).commit())
            throw MediaFailure(FailureReason.STORAGE)
        var bytes = 0L
        var lastProgress = 0L
        onProgress(0, total)
        storage {
            val output = resolver.openOutputStream(row, "w") ?: throw MediaFailure(FailureReason.STORAGE)
            output.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    job.ensureActive()
                    val count = try { stream.read(buffer) } catch (e: IOException) { throw sourceFailure(readFailure, e) }
                    if (count < 0) break
                    if (count == 0 || (total != null && count > total - bytes)) throw MediaFailure(readFailure)
                    it.write(buffer, 0, count)
                    bytes += count
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 120) {
                        onProgress(bytes, total)
                        lastProgress = now
                    }
                }
            }
        }
        if (bytes == 0L || (total != null && bytes != total)) throw MediaFailure(readFailure)
        job.ensureActive()
        val published = storage {
            resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        if (published != 1) throw MediaFailure(FailureReason.STORAGE)
        pending.edit().remove(row.toString()).apply()
        onProgress(bytes, total)
        return row
    }

    private fun insert(format: MediaFormat, hint: String?, album: String): Uri {
        val collection = if (format.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                         else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = if (format.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val base = hint.orEmpty().substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim(' ', '.').take(56).ifBlank { "Evasi" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base-${UUID.randomUUID()}.${format.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$album/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return storage { resolver.insert(collection, values) } ?: throw MediaFailure(FailureReason.STORAGE)
    }

    /** A worker may have published the row immediately before process death and before its Room commit. */
    fun publishedOutput(key: String): Uri? {
        val value = receipts.getString(key, null) ?: return null
        val row = Uri.parse(value)
        val existing = storage {
            val fields = arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE)
            val cursor = resolver.query(row, fields, null, null, null) ?: throw MediaFailure(FailureReason.STORAGE)
            cursor.use {
                if (!it.moveToFirst() || it.getInt(0) != 0) return@use false
                // Some providers refresh the indexed size asynchronously after publication.
                it.getLong(1) > 0 || resolver.openFileDescriptor(row, "r")?.use { fd -> fd.statSize > 0 } == true
            }
        }
        return row.takeIf { existing }
    }

    fun forgetOutput(key: String) {
        receipts.edit().remove(key).apply()
    }

    private inline fun <T> storage(action: () -> T): T {
        try {
            return action()
        } catch (e: IOException) {
            throw MediaFailure(storageFailure(e), e)
        } catch (e: RuntimeException) {
            if (e is java.util.concurrent.CancellationException) throw e
            throw MediaFailure(storageFailure(e), e)
        }
    }
}

const val DEFAULT_ALBUM = "eVasi Video Downloader"

/** User-selected folder relative to the system Pictures/Movies collection. */
internal fun normalizeAlbum(value: String): String {
    val path = value.trim().ifEmpty { DEFAULT_ALBUM }
    val parts = path.split('/')
    if (path.length > 160 || parts.any { it.isBlank() || it == "." || it == ".." || it.length > 80 ||
            it != it.trim() || it.startsWith('.') || it.any { ch -> ch.code < 32 || ch in "\\:*?\"<>|" } }) {
        throw MediaFailure(FailureReason.STORAGE)
    }
    return path
}

private fun storageFailure(error: Throwable): FailureReason {
    if (error is SecurityException) return FailureReason.PERMISSION
    return if (generateSequence(error) { it.cause }.any {
        (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC) ||
            it.message.orEmpty().contains("no space left", true)
    }) FailureReason.NO_SPACE else FailureReason.STORAGE
}

private fun sourceFailure(fallback: FailureReason, error: IOException): MediaFailure =
    MediaFailure(if (error is SocketTimeoutException) FailureReason.TIMEOUT else fallback, error)
