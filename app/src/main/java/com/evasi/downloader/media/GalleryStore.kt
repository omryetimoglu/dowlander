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
import java.util.UUID

internal class GalleryStore(context: Context) {
    private val resolver = context.contentResolver
    private val pending = context.getSharedPreferences("gallery_pending", Context.MODE_PRIVATE)

    private companion object {
        val lock = Any()
        var recovered = false
        const val album = "eVasi Dowlander App"
    }

    // Only rows inserted by this app are journaled. A completed row survives a
    // process death between publication and removing its journal entry.
    fun recover() = synchronized(lock) {
        if (recovered) return@synchronized
        for (key in pending.all.keys) {
            val row = Uri.parse(key)
            runCatching {
                val isPending = resolver.query(row, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
                    ?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: return@runCatching
                if (isPending) resolver.delete(row, null, null)
                pending.edit().remove(key).apply()
            }
        }
        recovered = true
    }

    fun discard(row: Uri) {
        runCatching {
            resolver.delete(row, null, null)
            pending.edit().remove(row.toString()).apply()
        }
    }

    fun write(
        input: InputStream, declared: String?, name: String?, total: Long?, readFailure: FailureReason,
        kind: MediaKind?, job: Job, onInsert: (Uri) -> Unit, onProgress: (Long, Long?) -> Unit,
    ): Uri {
        job.ensureActive()
        val stream = input.buffered(64 * 1024)
        val format = MediaFormat.inspect(stream, declared)
        if ((kind == MediaKind.VIDEO && !format.isVideo) || (kind == MediaKind.PHOTO && format.isVideo) ||
            (kind == MediaKind.GIF && !format.isVideo && format.extension !in setOf("gif", "webp", "avif"))) {
            throw MediaFailure(FailureReason.INVALID)
        }
        val row = insert(format, name)
        onInsert(row)
        if (!pending.edit().putBoolean(row.toString(), true).commit()) throw MediaFailure(FailureReason.STORAGE)
        var bytes = 0L
        var lastProgress = 0L
        onProgress(0, total)
        storage {
            val output = resolver.openOutputStream(row, "w") ?: throw MediaFailure(FailureReason.STORAGE)
            output.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    job.ensureActive()
                    val count = try { stream.read(buffer) } catch (e: IOException) { throw MediaFailure(readFailure, e) }
                    if (count < 0) break
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

    private fun insert(format: MediaFormat, hint: String?): Uri {
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

    private inline fun <T> storage(action: () -> T): T {
        try {
            return action()
        } catch (e: IOException) {
            throw MediaFailure(FailureReason.STORAGE, e)
        } catch (e: RuntimeException) {
            if (e is java.util.concurrent.CancellationException) throw e
            throw MediaFailure(FailureReason.STORAGE, e)
        }
    }
}
