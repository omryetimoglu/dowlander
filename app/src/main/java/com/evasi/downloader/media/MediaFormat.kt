package com.evasi.downloader.media

import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets

/** Gallery collection and filename follow the bytes, including GIFs hosted as MP4s. */
internal data class MediaFormat(val mime: String, val extension: String) {
    val isVideo: Boolean get() = mime.startsWith("video/")

    companion object {
        fun inspect(stream: BufferedInputStream, declared: String?): MediaFormat {
            val header = ByteArray(64)
            stream.mark(header.size)
            var count = 0
            while (count < header.size) {
                val read = stream.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            stream.reset()
            val data = String(header, 0, count, StandardCharsets.ISO_8859_1)
            return when {
                count >= 14 && (data.startsWith("GIF87a") || data.startsWith("GIF89a")) -> MediaFormat("image/gif", "gif")
                count >= 12 && data.startsWith("\u00ff\u00d8\u00ff") -> MediaFormat("image/jpeg", "jpg")
                count >= 24 && data.startsWith("\u0089PNG\r\n\u001a\n") -> MediaFormat("image/png", "png")
                data.startsWith("RIFF") && data.startsWith("WEBP", 8) -> MediaFormat("image/webp", "webp")
                data.startsWith("RIFF") && data.startsWith("AVI ", 8) -> MediaFormat("video/avi", "avi")
                count >= 16 && data.startsWith("ftyp", 4) -> isoMedia(data, declared)
                data.startsWith("OggS") && (declared == "video/ogg" || data.contains("theora")) -> MediaFormat("video/ogg", "ogv")
                data.startsWith("FLV") && count >= 13 -> MediaFormat("video/x-flv", "flv")
                data.startsWith("G") && declared == "video/mp2t" -> MediaFormat("video/mp2t", "ts")
                data.startsWith("\u001a\u0045\u00df\u00a3") -> if (declared == "video/x-matroska" || data.contains("matroska")) {
                    MediaFormat("video/x-matroska", "mkv")
                } else MediaFormat("video/webm", "webm")
                data.startsWith("BM") && count >= 26 -> MediaFormat("image/bmp", "bmp")
                data.startsWith("II*\u0000") || data.startsWith("MM\u0000*") -> MediaFormat("image/tiff", "tif")
                data.startsWith("\u0000\u0000\u0001\u00ba") || data.startsWith("\u0000\u0000\u0001\u00b3") -> MediaFormat("video/mpeg", "mpg")
                else -> throw MediaFailure(FailureReason.INVALID)
            }
        }

        private fun isoMedia(header: String, declared: String?): MediaFormat {
            val brand = header.drop(8).take(4)
            return when {
                brand in setOf("avif", "avis") || header.contains("avif") -> MediaFormat("image/avif", "avif")
                brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis") -> MediaFormat("image/heic", "heic")
                brand in setOf("mif1", "msf1") -> MediaFormat("image/heif", "heif")
                brand.startsWith("3g2") -> MediaFormat("video/3gpp2", "3g2")
                brand.startsWith("3g") -> MediaFormat("video/3gpp", "3gp")
                brand == "qt  " || declared == "video/quicktime" -> MediaFormat("video/quicktime", "mov")
                brand in setOf("M4A ", "M4B ", "f4a ") || declared?.startsWith("audio/") == true ->
                    throw MediaFailure(FailureReason.UNSUPPORTED)
                else -> MediaFormat("video/mp4", "mp4")
            }
        }
    }
}
