package com.evasi.downloader.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

class MediaRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val gallery = GalleryStore(app)

    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) { gallery.recover() }

    suspend fun resolve(link: String): MediaPost = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        val endpoint = Mastodon.endpoint(link)
        http(endpoint, "application/json", job) { response ->
            response.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    job.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 2 * 1024 * 1024) throw MediaFailure(FailureReason.UNSUPPORTED)
                    bytes.write(buffer, 0, count)
                }
                try {
                    Mastodon.parse(bytes.toString("UTF-8"))
                } catch (e: JSONException) {
                    throw MediaFailure(FailureReason.UNSUPPORTED, e)
                }
            }
        }
    }

    suspend fun resolveShared(uri: Uri, mimeType: String?): MediaPost = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw MediaFailure(FailureReason.INVALID)
        val name = sharedInfo(uri).first
        val hint = listOfNotNull(runCatching { resolver.getType(uri) }.getOrNull(), mimeType,
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name?.substringAfterLast('.', "")?.lowercase()))
            .firstOrNull { it.startsWith("image/") || it.startsWith("video/") }
        // Some document providers only report application/octet-stream. Inspect those
        // through their temporary read grant, without trusting an arbitrary extension.
        val kind = when {
            hint == "image/gif" -> MediaKind.GIF
            hint?.startsWith("video/") == true -> MediaKind.VIDEO
            hint?.startsWith("image/") == true -> MediaKind.PHOTO
            else -> {
                val format = withSharedInput(uri) { MediaFormat.inspect(it.buffered(), hint) }
                when {
                    format.isVideo -> MediaKind.VIDEO
                    format.mime == "image/gif" -> MediaKind.GIF
                    else -> MediaKind.PHOTO
                }
            }
        }
        MediaPost(listOf(MediaItem(kind, listOf(MediaSource(uri.toString(), hint, name = name)))), name.orEmpty())
    }

    suspend fun save(item: MediaItem, quality: Quality?, onProgress: (bytes: Long, total: Long?) -> Unit): Uri {
        val source = if (quality == null) item.sources.firstOrNull() else item.sources.find { it.quality == quality }
        if (source == null) throw MediaFailure(FailureReason.UNSUPPORTED)
        var inserted: Uri? = null
        // Keep the row until the caller actually receives the result. withContext may
        // discard an IO result if cancellation wins the dispatch back to the UI.
        try {
            return withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                gallery.recover()
                val uri = Uri.parse(source.uri)
                val onInsert: (Uri) -> Unit = { inserted = it }
                when (uri.scheme?.lowercase()) {
                    "content" -> withSharedInput(uri) { stream ->
                        gallery.write(stream, source.mime, source.name, sharedInfo(uri).second,
                            FailureReason.INVALID, null, job, onInsert, onProgress)
                    }
                    "https" -> http(URI(source.uri), "image/*, video/*, application/octet-stream", job) { response ->
                        response.inputStream.use { stream ->
                            val total = response.contentLengthLong.takeIf { it >= 0 }
                            gallery.write(stream, response.contentType?.substringBefore(';'), source.name,
                                total, FailureReason.NETWORK, item.kind, job, onInsert, onProgress)
                        }
                    }
                    else -> throw MediaFailure(FailureReason.UNSUPPORTED)
                }
            }
        } catch (error: Throwable) {
            inserted?.let { row -> withContext(Dispatchers.IO + NonCancellable) { gallery.discard(row) } }
            throw error
        }
    }

    private fun sharedInfo(uri: Uri): Pair<String?, Long?> {
        // OpenableColumns are optional for some stream and Photos providers.
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                Pair(if (name >= 0) cursor.getString(name) else null,
                    if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size).takeIf { it >= 0 } else null)
            }
        }.getOrNull() ?: Pair(null, null)
    }

    private fun <T> withSharedInput(uri: Uri, block: (InputStream) -> T): T {
        try {
            return resolver.openInputStream(uri)?.use(block) ?: throw MediaFailure(FailureReason.INVALID)
        } catch (e: IOException) {
            throw MediaFailure(FailureReason.INVALID, e)
        } catch (e: SecurityException) {
            throw MediaFailure(FailureReason.UNAVAILABLE, e)
        }
    }

    private fun <T> http(start: URI, accept: String, job: Job, block: (HttpURLConnection) -> T): T {
        var target = start
        try {
            repeat(6) {
                job.ensureActive()
                if (!target.scheme.equals("https", true) || target.host == null || target.userInfo != null) {
                    throw MediaFailure(FailureReason.UNSUPPORTED)
                }
                val connection = target.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", accept)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("User-Agent", "EvasiVideoDowlander/1.0 (Android)")
                    when (val status = connection.responseCode) {
                        200 -> return block(connection)
                        301, 302, 303, 307, 308 -> {
                            val next = connection.getHeaderField("Location") ?: throw MediaFailure(FailureReason.UNAVAILABLE)
                            target = runCatching { target.resolve(next) }.getOrElse { throw MediaFailure(FailureReason.UNAVAILABLE, it) }
                        }
                        401, 403, 404, 410, 451 -> throw MediaFailure(FailureReason.UNAVAILABLE)
                        408, 429, in 500..599 -> throw MediaFailure(FailureReason.NETWORK)
                        else -> throw MediaFailure(if (status in 400..499) FailureReason.UNSUPPORTED else FailureReason.UNAVAILABLE)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw MediaFailure(FailureReason.UNAVAILABLE)
        } catch (e: IOException) {
            job.ensureActive()
            throw MediaFailure(FailureReason.NETWORK, e)
        }
    }
}
