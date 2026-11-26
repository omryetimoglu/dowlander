package com.evasi.downloader.media

import android.content.Context
import com.evasi.downloader.R
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
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
        val request = PlatformVideoRequest.forLink(link).addOption("--dump-json")
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
            val request = PlatformVideoRequest.forLink(source.uri, source.quality)
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
    fun forLink(link: String, quality: Quality? = null): YoutubeDLRequest {
        val size = quality?.let { "[width=${it.width}][height=${it.height}]" }.orEmpty()
        // Prefer MP4, with audio whenever present. bv is the final fallback for genuinely silent videos.
        val format = "bv*$size[ext=mp4]+ba[ext=m4a]/b$size[ext=mp4]/bv*$size+ba/b$size/bv$size"
        return YoutubeDLRequest(link)
            .addOption("--ignore-config")
            .addOption("--no-playlist")
            .addOption("--playlist-items", "1")
            .addOption("--match-filters", "!is_live")
            .addOption("--socket-timeout", "20")
            .addOption("-f", format)
    }

    fun parse(output: String, link: String): MediaPost {
        if (output.isBlank()) throw MediaFailure(FailureReason.UNAVAILABLE)
        val video = JSONObject(output)
        if (video.optBoolean("is_live")) throw MediaFailure(FailureReason.UNSUPPORTED)
        val formats = video.optJSONArray("formats") ?: throw MediaFailure(FailureReason.UNSUPPORTED)
        val sizes = linkedSetOf<Quality>()
        var playable = false
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            if (format.optBoolean("has_drm") || format.optString("vcodec") == "none" ||
                format.optString("ext") in setOf("mhtml", "vtt")) continue
            playable = true
            val width = format.optInt("width")
            val height = format.optInt("height")
            if (width > 0 && height > 0) sizes += Quality(width, height)
        }
        if (!playable) throw MediaFailure(FailureReason.UNSUPPORTED)
        val title = video.optString("title").take(120)
        val automatic = MediaSource(link, name = title.ifBlank { "Video" } + ".mp4", platformVideo = true)
        return MediaPost(listOf(MediaItem(MediaKind.VIDEO, listOf(automatic) + sizes.map { automatic.copy(quality = it) })), title)
    }

    fun failure(message: String): FailureReason = when {
        message.contains("No space left", true) || message.contains("failed to initialize", true) -> FailureReason.STORAGE
        listOf("timed out", "unable to connect", "unable to download webpage", "resolve host", "HTTP Error 429", "temporary failure")
            .any { message.contains(it, ignoreCase = true) } && !message.contains("HTTP Error 40") -> FailureReason.NETWORK
        message.contains("Unsupported URL", true) || message.contains("Requested format is not available", true) -> FailureReason.UNSUPPORTED
        else -> FailureReason.UNAVAILABLE
    }
}
