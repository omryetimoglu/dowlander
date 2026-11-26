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
                .addOption("--progress-template", "download:bytes %(progress.downloaded_bytes)s %(progress.total_bytes,progress.total_bytes_estimate)s")
                .addOption("--progress-delta", "0.4")
                .addOption("--merge-output-format", "mp4/mkv")
                .addOption("--abort-on-unavailable-fragments")
            execute(request) { line ->
                if (line.startsWith("bytes ")) {
                    val fields = line.split(' ')
                    fields.getOrNull(1)?.toLongOrNull()?.let { bytes ->
                        onProgress(bytes, fields.getOrNull(2)?.toLongOrNull())
                    }
                }
            }
            val file = temp.listFiles()?.singleOrNull { it.isFile && it.nameWithoutExtension == "video" && it.length() > 0L }
                ?: throw MediaFailure(FailureReason.UNAVAILABLE)
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
                // The wrapper normally retains its unpacked zipapp forever, even across APK updates.
                // Install the current APK's copy atomically before opening the runtime.
                val dir = File(File(app.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
                Files.createDirectories(dir.toPath())
                val temp = Files.createTempFile(dir.toPath(), "yt-dlp", ".tmp")
                try {
                    app.resources.openRawResource(R.raw.ytdlp).use { input ->
                        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.move(temp, File(dir, YoutubeDL.ytdlpBin).toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
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
        } ?: "bv*$size[ext=mp4]+ba[ext=m4a]/b$size[ext=mp4]/bv*$size+ba/b$size/bv$size"
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
        val items = if (entries == null) listOfNotNull(parseItem(post, link, null)) else {
            // An album with a missing attachment must not be reported as fully saved.
            (0 until entries.length()).map { position ->
                val entry = entries.optJSONObject(position) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
                val index = entry.optInt("playlist_index").takeIf { it > 0 } ?: (position + 1)
                parseItem(entry, link, index) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            }
        }
        if (items.isEmpty()) throw MediaFailure(FailureReason.UNSUPPORTED)
        return MediaPost(items, post.optString("title").take(120))
    }

    private fun parseItem(entry: JSONObject, link: String, index: Int?): MediaItem? {
        if (entry.optBoolean("is_live") || entry.optBoolean("has_drm")) return null
        val array = entry.optJSONArray("formats")
        val formats = if (array == null || array.length() == 0) listOf(entry).filter { !it.isNull("url") }
            else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        val downloadable = formats.filterNot {
            it.optBoolean("has_drm") || it.optString("ext") in setOf("mhtml", "vtt") ||
                it.optString("format_note").contains("storyboard", ignoreCase = true)
        }
        val videos = downloadable.filter { imageType(it) == null && it.optString("vcodec") != "none" }
        val title = entry.optString("title").take(120).ifBlank { "Media" }
        val name = if (index == null) title else "$index-$title"
        val source = MediaSource(link, name = "$name.mp4", platformVideo = true, playlistIndex = index)
        if (videos.isNotEmpty()) {
            val sizes = videos.mapNotNull(::quality).distinct()
            return MediaItem(MediaKind.VIDEO, listOf(source) + sizes.map { source.copy(quality = it) })
        }

        // Never use thumbnail/thumbnails. Photos must be independently downloadable payloads.
        val photos = downloadable.mapNotNull { format ->
            val mime = imageType(format) ?: return@mapNotNull null
            val uri = runCatching { URI(format.optString("url")) }.getOrNull() ?: return@mapNotNull null
            if (uri.host == null || uri.scheme !in setOf("http", "https")) return@mapNotNull null
            val id = format.optString("format_id").takeIf(formatId::matches) ?: return@mapNotNull null
            source.copy(mime = mime, quality = quality(format), name = "$name.${mime.substringAfter('/')}",
                platformImageFormat = id)
        }.sortedByDescending { it.quality?.let { q -> q.width.toLong() * q.height } ?: 0L }
        val original = photos.firstOrNull() ?: return null
        return MediaItem(if (original.mime == "image/gif") MediaKind.GIF else MediaKind.PHOTO, photos)
    }

    private fun imageType(format: JSONObject): String? = imageTypes[format.optString("ext").lowercase()]

    private fun quality(format: JSONObject): Quality? {
        val w = format.optInt("width")
        val h = format.optInt("height")
        return if (w > 0 && h > 0) Quality(w, h) else null
    }

    fun failure(message: String): FailureReason = when {
        message.contains("No space left", true) || message.contains("failed to initialize", true) -> FailureReason.STORAGE
        listOf("timed out", "unable to connect", "unable to download webpage", "resolve host", "HTTP Error 429", "temporary failure")
            .any { message.contains(it, ignoreCase = true) } && !message.contains("HTTP Error 40") -> FailureReason.NETWORK
        message.contains("Unsupported URL", true) || message.contains("Requested format is not available", true) -> FailureReason.UNSUPPORTED
        else -> FailureReason.UNAVAILABLE
    }
}
