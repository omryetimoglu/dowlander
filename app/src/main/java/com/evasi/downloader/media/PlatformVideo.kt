package com.evasi.downloader.media

import android.content.Context
import com.evasi.downloader.R
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** yt-dlp manages signed URLs, request headers, segmented downloads and audio/video merging. */
internal class PlatformVideo(private val app: Context) {
    suspend fun resolve(link: String): MediaPost {
        val request = PlatformVideoRequest.metadata(link)
        val metadata = execute(request)
        return try {
            PlatformVideoRequest.parse(metadata, link)
        } catch (error: JSONException) {
            throw MediaFailure(FailureReason.UNSUPPORTED, error)
        }
    }

    suspend fun <T> download(
        source: MediaSource,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        consume: suspend (File) -> T,
    ): T = withContext(Dispatchers.IO) {
        val temp = try {
            Files.createTempDirectory(app.cacheDir.toPath(), "evasi-video-").toFile()
        } catch (error: IOException) {
            throw MediaFailure(FailureReason.STORAGE, error)
        }
        try {
            val request = PlatformVideoRequest.download(source)
                .addOption("-o", File(temp, "video.%(ext)s").absolutePath)
                .addOption("--newline")
                .addOption("--progress-template", "download:bytes %(info.format_id)s %(progress.downloaded_bytes)s %(progress.total_bytes,progress.total_bytes_estimate)s")
                .addOption("--progress-delta", "0.4")
                .addOption("--merge-output-format", "mp4/mkv")
                .addOption("--abort-on-unavailable-fragments")
            val progress = NativeTransferProgress(source.estimatedBytes, source.nativeStreamCount, onProgress)
            execute(request, progress::update)
            val file = temp.listFiles()?.singleOrNull { it.isFile && it.nameWithoutExtension == "video" && it.length() > 0L }
                ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            progress.complete(file.length())
            consume(file)
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun execute(request: YoutubeDLRequest, onLine: ((String) -> Unit)? = null): String {
        return try {
            // The wrapper destroys its running process if the waiting thread is interrupted.
            runInterruptible(Dispatchers.IO) {
                initialize(app)
                YoutubeDL.execute(request, processId = null, redirectErrorStream = false,
                    callback = onLine?.let { listener -> { _, _, line -> listener(line) } }).out
            }
        } catch (error: YoutubeDLException) {
            throw MediaFailure(PlatformVideoRequest.failure(error.message.orEmpty()), error)
        }
    }
    private companion object {
        private var initialized = false

        @Synchronized
        fun initialize(app: Context) {
            if (initialized) return
            try {
                val dir = File(File(app.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
                @Suppress("DEPRECATION") // Common API across the app's supported Android versions.
                val updatedAt = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                val apk = "${app.applicationInfo.sourceDir}:$updatedAt"
                installPackagedExtractor(File(dir, YoutubeDL.ytdlpBin), apk) {
                    app.resources.openRawResource(R.raw.ytdlp)
                }
            } catch (error: IOException) {
                throw MediaFailure(FailureReason.STORAGE, error)
            }
            YoutubeDL.init(app)
            FFmpeg.init(app)
            initialized = true
        }
    }
}

/** Install once per APK, including replacements that keep versionCode. A stale/partial marker is a cache miss. */
internal fun installPackagedExtractor(target: File, apk: String, openResource: () -> InputStream) {
    val dir = target.parentFile!!
    val marker = File(dir, "${target.name}.installed")
    fun stamp() = "$apk\n${target.length()}\n${target.lastModified()}"
    if (target.isFile && target.length() > 0 && marker.isFile && marker.readText() == stamp()) return

    Files.createDirectories(dir.toPath())
    val temp = Files.createTempFile(dir.toPath(), "yt-dlp", ".tmp")
    try {
        val copied = openResource().use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
        if (copied == 0L) throw IOException("Empty packaged extractor")
        Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        marker.writeText(stamp())
    } finally {
        Files.deleteIfExists(temp)
    }
}

internal object PlatformVideoRequest {
    private val imageTypes = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "avif" to "image/avif", "heic" to "image/heic",
    )
    private val formatId = Regex("[A-Za-z0-9_.-]+")

    private fun request(link: String): YoutubeDLRequest = YoutubeDLRequest(link)
        .addOption("--ignore-config")
        .addOption("--match-filters", "!is_live")
        .addOption("--socket-timeout", "20")
        .addOption("-S", "res,fps,codec:h264:aac")

    fun metadata(link: String): YoutubeDLRequest {
        val request = request(link).addOption("--dump-single-json").addOption("--ignore-no-formats-error")
        if (SocialVideoLinks.match(URI(link))?.host == "www.youtube.com") {
            request.addOption("--no-playlist").addOption("--playlist-items", "1")
        }
        return request
    }

    fun download(source: MediaSource): YoutubeDLRequest {
        val size = source.quality?.let { "[width=${it.width}][height=${it.height}]" }.orEmpty()
        // Image-only formats can have both codecs set to "none". Select their exact ID.
        val format = source.platformImageFormat?.let { id ->
            require(formatId.matches(id))
            id
        } ?: "bv*$size+ba/b$size/bv$size"
        val result = request(source.uri)
            .addOption("--playlist-items", source.playlistIndex ?: 1)
            .addOption("-f", format)
        if (source.playlistIndex == null) result.addOption("--no-playlist")
        return result
    }

    fun parse(output: String, link: String): MediaPost {
        if (output.isBlank()) throw MediaFailure(FailureReason.UNAVAILABLE)
        val post = JSONObject(output)
        val entries = post.optJSONArray("entries")
        val provider = SocialVideoLinks.match(URI(link))?.host
        val items = if (entries == null) listOfNotNull(parseItem(post, link, provider, null)) else {
            // An album with a missing attachment must not be reported as fully saved.
            (0 until entries.length()).map { position ->
                val entry = entries.optJSONObject(position) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
                val index = entry.optInt("playlist_index").takeIf { it > 0 } ?: (position + 1)
                parseItem(entry, link, provider, index) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            }
        }
        if (items.isEmpty()) throw MediaFailure(FailureReason.UNSUPPORTED)
        val first = entries?.optJSONObject(0) ?: post
        val caption = post.caption("description") ?: first.caption("description")
        return MediaPost(items, publicTitle(post, provider), caption, preview(post.caption("thumbnail") ?: first.caption("thumbnail")))
    }

    private fun publicTitle(node: JSONObject, provider: String?): String {
        // Social extractors frequently build "title" from a truncated caption. It must not
        // appear in a guest's filename, queue, or history outside the signed-in text action.
        val title = if (provider == "www.youtube.com" || provider == null)
            node.caption("title")?.takeUnless { it == node.caption("description") } else null
        return (title ?: node.caption("uploader") ?: node.caption("channel") ?: node.caption("extractor_key")
            ?: provider?.removePrefix("www.") ?: "Media").take(120)
    }

    private fun parseItem(entry: JSONObject, link: String, provider: String?, index: Int?): MediaItem? {
        if (entry.optBoolean("is_live") || entry.optBoolean("has_drm")) return null
        val array = entry.optJSONArray("formats")
        val formats = if (array == null || array.length() == 0) listOf(entry).filter { !it.isNull("url") }
            else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        val downloadable = formats.filterNot {
            it.optBoolean("has_drm") || it.optString("ext") in setOf("mhtml", "vtt") ||
                it.optString("format_note").contains("storyboard", ignoreCase = true)
        }
        val videos = downloadable.filter { imageType(it) == null && it.optString("vcodec") != "none" }
        val title = publicTitle(entry, provider)
        val name = if (index == null) title else "$index-$title"
        val source = MediaSource(link, name = "$name.mp4", platformVideo = true, playlistIndex = index)
        if (videos.isNotEmpty()) {
            // yt-dlp returns formats in ascending preference order, using the sort order on the request.
            val audio = downloadable.lastOrNull { it.optString("vcodec") == "none" && it.optString("acodec") != "none" }
            fun estimate(format: JSONObject): Long? {
                val videoBytes = formatBytes(format, entry) ?: return null
                if (format.optString("acodec") != "none" || audio == null) return videoBytes
                val audioBytes = formatBytes(audio, entry) ?: return null
                return (videoBytes + audioBytes).takeIf { it > 0 }
            }
            fun version(format: JSONObject, size: Quality?) = source.copy(quality = size,
                estimatedBytes = estimate(format), nativeStreamCount = when {
                    format.optString("acodec") == "none" && audio != null -> 2
                    !format.isNull("acodec") -> 1
                    else -> null
                })
            val sizes = videos.asReversed().mapNotNull { format ->
                quality(format)?.let { version(format, it) }
            }.distinctBy { it.quality }
            return MediaItem(MediaKind.VIDEO, listOf(version(videos.last(), null)) + sizes)
        }

        // Never use thumbnail/thumbnails. Photos must be independently downloadable payloads.
        val photos = downloadable.mapNotNull { format ->
            val mime = imageType(format) ?: return@mapNotNull null
            val uri = runCatching { URI(format.optString("url")) }.getOrNull() ?: return@mapNotNull null
            if (uri.host == null || uri.scheme !in setOf("http", "https")) return@mapNotNull null
            val id = format.optString("format_id").takeIf(formatId::matches) ?: return@mapNotNull null
            source.copy(mime = mime, quality = quality(format), name = "$name.${mime.substringAfter('/')}",
                platformImageFormat = id, estimatedBytes = formatBytes(format, entry), nativeStreamCount = 1)
        }.sortedByDescending { it.quality?.let { q -> q.width.toLong() * q.height } ?: 0L }
        val original = photos.firstOrNull() ?: return null
        return MediaItem(if (original.mime == "image/gif") MediaKind.GIF else MediaKind.PHOTO, photos)
    }

    private fun formatBytes(format: JSONObject, entry: JSONObject): Long? {
        format.bytes("filesize")?.let { return it }
        format.bytes("filesize_approx")?.let { return it }
        // yt-dlp reports total bitrate in kilobits/second, duration in seconds.
        return (format.optDouble("tbr") * entry.optDouble("duration") * 125.0)
            .takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE }?.toLong()
    }

    private fun imageType(format: JSONObject): String? = imageTypes[format.optString("ext").lowercase()]

    private fun quality(format: JSONObject): Quality? {
        val w = format.optInt("width")
        val h = format.optInt("height")
        return if (w > 0 && h > 0) Quality(w, h) else null
    }

    fun failure(message: String): FailureReason = when {
        message.contains("No space left", true) -> FailureReason.NO_SPACE
        message.contains("failed to initialize", true) -> FailureReason.STORAGE
        listOf("private video", "private account", "login required").any { message.contains(it, true) } -> FailureReason.PRIVATE
        listOf("has been deleted", "has been removed", "HTTP Error 410", "HTTP Error 404").any { message.contains(it, true) } -> FailureReason.DELETED
        message.contains("timed out", true) -> FailureReason.TIMEOUT
        listOf("timed out", "unable to connect", "unable to download webpage", "resolve host", "HTTP Error 429", "temporary failure")
            .any { message.contains(it, ignoreCase = true) } && !message.contains("HTTP Error 40") -> FailureReason.NETWORK
        message.contains("Unsupported URL", true) || message.contains("Requested format is not available", true) -> FailureReason.UNSUPPORTED
        else -> FailureReason.UNAVAILABLE
    }
}
