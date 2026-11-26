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

class MediaRepository internal constructor(
    context: Context,
    private val openConnection: (URI) -> HttpURLConnection,
) {
    constructor(context: Context) : this(context, { it.toURL().openConnection() as HttpURLConnection })

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val gallery = GalleryStore(app)
    private val platformVideo by lazy { PlatformVideo(app) }

    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) { gallery.recover() }

    suspend fun resolve(link: String): MediaPost = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        val uri = runCatching { URI(link) }.getOrNull() ?: throw MediaFailure(FailureReason.INVALID)
        val videoLink = SocialVideoLinks.match(uri)
        if (videoLink != null) {
            return@withContext resolveSocialAlbum(videoLink, job) ?: platformVideo.resolve(videoLink.toString())
        }
        val request = PostRequest.from(link)
        http(request.endpoint, "application/json", job) { response ->
            try {
                request.parse(readBody(response, job, 2 * 1024 * 1024))
            } catch (e: JSONException) {
                throw MediaFailure(FailureReason.UNSUPPORTED, e)
            }
        }
    }

    private fun resolveSocialAlbum(link: URI, job: Job): MediaPost? {
        val instagram = InstagramAlbum.endpoint(link)
        val facebook = FacebookAlbum.endpoint(link)
        val threads = ThreadsAlbum.endpoint(link)
        val tiktok = TikTokAlbum.isCandidate(link) || TikTokAlbum.isVideoCandidate(link)
        if (instagram == null && facebook == null && threads == null && !tiktok) return null

        val pageHosts = when {
            instagram != null -> setOf("instagram.com", "instagr.am")
            facebook != null -> setOf("facebook.com", "fb.watch")
            threads != null -> setOf("threads.com", "threads.net")
            else -> setOf("tiktok.com")
        }
        fun fetchPage(url: URI): Pair<URI, String> = http(url, "text/html", job, browser = true,
            allowedHosts = pageHosts) { response ->
            response.url.toURI() to readBody(response, job, 8 * 1024 * 1024)
        }

        // A video can still be available to the native extractor when the HTML page is unavailable.
        // Once the page identifies a carousel, propagate parser/child failures to avoid partial albums.
        var receivedPage = false
        fun requestPage(url: URI) = fetchPage(url).also { receivedPage = true }
        return try {
            when {
                instagram != null -> {
                    val (page, html) = requestPage(instagram)
                    InstagramAlbum.parse(html, page)
                }
                facebook != null -> {
                    val (page, html) = requestPage(facebook)
                    FacebookAlbum.parse(html, page) { photo -> fetchPage(photo).second }
                }
                threads != null -> {
                    val (page, html) = requestPage(threads)
                    ThreadsAlbum.parse(html, page) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
                }
                else -> TikTokAlbum.probe(link, ::requestPage)
            }
        } catch (error: MediaFailure) {
            if (receivedPage || threads != null || link.path.contains("/photo/") || link.path == "/photo.php") throw error
            null
        }
    }

    private fun readBody(response: HttpURLConnection, job: Job, limit: Int): String {
        response.inputStream.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                job.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > limit) throw MediaFailure(FailureReason.UNSUPPORTED)
                bytes.write(buffer, 0, count)
            }
            return bytes.toString("UTF-8")
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
                if (source.platformVideo) {
                    return@withContext platformVideo.download(source, onProgress) { file ->
                        file.inputStream().use { input ->
                            gallery.write(input, source.mime, source.name, file.length(), FailureReason.STORAGE,
                                item.kind, job, onInsert, onProgress)
                        }
                    }
                }
                when (uri.scheme?.lowercase()) {
                    "content" -> withSharedInput(uri) { stream ->
                        gallery.write(stream, source.mime, source.name, sharedInfo(uri).second,
                            FailureReason.INVALID, null, job, onInsert, onProgress)
                    }
                    "https" -> http(URI(source.uri), "image/*, video/*, application/octet-stream", job, referer = source.referer) { response ->
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

    private fun <T> http(
        start: URI, accept: String, job: Job, browser: Boolean = false, referer: String? = null,
        allowedHosts: Set<String>? = null, block: (HttpURLConnection) -> T,
    ): T {
        var target = start
        try {
            repeat(6) {
                job.ensureActive()
                if (!target.scheme.equals("https", true) || target.host == null || target.userInfo != null) {
                    throw MediaFailure(FailureReason.UNSUPPORTED)
                }
                if (allowedHosts != null && (target.port !in setOf(-1, 443) || allowedHosts.none {
                    target.host.equals(it, true) || target.host.endsWith(".$it", true)
                })) throw MediaFailure(FailureReason.UNAVAILABLE)
                val connection = openConnection(target)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", accept)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("User-Agent", if (browser || referer != null)
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        else "eVasiDowlanderApp/1.2 (Android)")
                    if (referer != null) connection.setRequestProperty("Referer", referer)
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
