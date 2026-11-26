package com.evasi.downloader.queue

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.evasi.downloader.Incoming
import com.evasi.downloader.media.FailureReason
import com.evasi.downloader.media.Mastodon
import com.evasi.downloader.media.MediaFailure
import com.evasi.downloader.media.SocialVideoLinks
import com.evasi.downloader.media.XPost
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class StagedInput(
    val payload: String,
    val dedupeKey: String,
    val requiresNetwork: Boolean,
    val sourceUrl: String?,
    val platform: String,
    val previewLabel: String,
)

/** Copy transient read grants before queue insertion. Workers open only our own staged copies. */
internal class QueueInputs(context: Context) {
    private val app = context.applicationContext
    private val root get() = File(app.filesDir, "queue-inputs")
    private val authority get() = "${app.packageName}.queue-inputs"

    suspend fun prepare(input: Incoming, jobId: String): StagedInput = withContext(Dispatchers.IO) {
        when (input) {
            is Incoming.Link -> stageLink(input.url)
            is Incoming.Files -> stageFiles(input, jobId)
            Incoming.Invalid -> throw MediaFailure(FailureReason.INVALID)
        }
    }

    private fun stageLink(url: String): StagedInput {
        val uri = try { URI(url) } catch (error: java.net.URISyntaxException) { throw MediaFailure(FailureReason.INVALID, error) }
        val social = SocialVideoLinks.match(uri)
        val xId = XPost.statusId(uri)
        val canonical = social?.toString() ?: xId?.let { "https://x.com/i/status/$it" } ?: Mastodon.endpoint(url).toString()
        val host = social?.host.orEmpty()
        val platform = when {
            xId != null -> "X"
            social == null -> "Mastodon"
            host.contains("instagram") -> "Instagram"
            host.contains("facebook") || host == "fb.watch" -> "Facebook"
            host.contains("tiktok") -> "TikTok"
            host.contains("threads") -> "Threads"
            host.contains("youtube") -> "YouTube"
            else -> host
        }
        // Preserve the post path for the resolver (Mastodon's endpoint is an API URL).
        val source = social?.toString() ?: if (xId != null) canonical else URI(uri.scheme, uri.authority, uri.path, null, null).toASCIIString()
        return StagedInput(JSONObject().put("link", source).toString(), "url:$canonical", true, source, platform, platform)
    }

    private suspend fun stageFiles(input: Incoming.Files, jobId: String): StagedInput {
        if (input.uris.isEmpty()) throw MediaFailure(FailureReason.INVALID)
        val directory = directory(jobId)
        if (!directory.mkdirs()) throw MediaFailure(FailureReason.STORAGE)
        try {
            val urls = JSONArray()
            val hashes = MessageDigest.getInstance("SHA-256")
            var firstName = ""
            val buffer = ByteArray(64 * 1024)
            for ((index, value) in input.uris.withIndex()) {
                val original = Uri.parse(value)
                if (original.scheme != "content" || original.authority.isNullOrBlank() || original.authority == authority) throw MediaFailure(FailureReason.INVALID)
                val (displayName, expectedBytes) = fileInfo(original)
                val name = displayName ?: "media"
                if (index == 0) firstName = name
                val copy = File(directory, "${index + 1}-$name")
                val digest = MessageDigest.getInstance("SHA-256")
                val source = app.contentResolver.openInputStream(original) ?: throw MediaFailure(FailureReason.INVALID)
                source.use { stream ->
                    copy.outputStream().use { output ->
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = try { stream.read(buffer) } catch (e: IOException) { throw MediaFailure(FailureReason.INVALID, e) }
                            if (n < 0) break
                            if (n == 0 || (expectedBytes != null && n > expectedBytes - copied))
                                throw MediaFailure(FailureReason.INVALID)
                            copied += n
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                        }
                        if (copied == 0L || (expectedBytes != null && copied != expectedBytes))
                            throw MediaFailure(FailureReason.INVALID)
                        output.fd.sync()
                    }
                }
                hashes.update(digest.digest())
                urls.put(FileProvider.getUriForFile(app, authority, copy, name).toString())
            }
            val payload = JSONObject().put("files", urls).apply { input.mime?.let { put("mime", it) } }
            return StagedInput(payload.toString(), "files:" + Base64.getEncoder().encodeToString(hashes.digest()),
                false, null, "Android", firstName)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw when (error) {
                is SecurityException -> MediaFailure(FailureReason.PERMISSION, error)
                is java.io.FileNotFoundException -> MediaFailure(FailureReason.UNAVAILABLE, error)
                is IOException -> MediaFailure(if (generateSequence<Throwable>(error) { it.cause }.any {
                    it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC ||
                        it.message.orEmpty().contains("no space left", true)
                }) FailureReason.NO_SPACE else FailureReason.STORAGE, error)
                else -> error
            }
        }
    }

    private fun fileInfo(uri: Uri): Pair<String?, Long?> {
        return try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let(cursor::getString)?.substringAfterLast('/')?.substringAfterLast('\\')
                    ?.replace(Regex("[\\p{Cntrl}]"), "_")?.takeLast(60)?.takeIf { it.isNotBlank() }
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)?.takeIf { it >= 0 }
                name to size
            }
        } catch (_: RuntimeException) { null } ?: (null to null)
    }

    fun restore(payload: String): Incoming {
        val json = JSONObject(payload)
        if (json.has("link")) return Incoming.Link(json.getString("link"))
        val files = json.getJSONArray("files")
        val urls = (0 until files.length()).map { index ->
            val value = files.getString(index)
            val uri = Uri.parse(value)
            require(uri.scheme == "content" && uri.authority == authority)
            value
        }
        return Incoming.Files(urls, if (json.has("mime")) json.getString("mime") else null)
    }

    fun discard(jobId: String) { directory(jobId).deleteRecursively() }

    /** A process can die after staging but before Room commits its queue row. */
    fun discardOrphans(liveIds: Set<String>) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        for (dir in root.listFiles().orEmpty()) {
            if (dir.name !in liveIds && dir.lastModified() < cutoff && dir.isDirectory &&
                runCatching { UUID.fromString(dir.name).toString() == dir.name }.getOrDefault(false)) dir.deleteRecursively()
        }
    }

    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, id)
    }
}
