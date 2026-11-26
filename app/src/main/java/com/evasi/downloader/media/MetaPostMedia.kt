package com.evasi.downloader.media

import org.json.JSONObject
import java.net.URI

/** The public Instagram and Threads responses use the same media and carousel fields. */
internal object MetaPostMedia {
    fun parse(post: JSONObject, code: String, platform: String, referer: String, includeSingleVideo: Boolean): MediaPost? {
        val carousel = post.optJSONArray("carousel_media")
        val sidecar = post.optJSONObject("edge_sidecar_to_children")
        val isAlbum = carousel != null || sidecar != null || post.optInt("media_type") == 8 ||
            post.optString("__typename").endsWith("Sidecar")
        val nodes = if (isAlbum) {
            val children = carousel ?: sidecar?.optJSONArray("edges") ?: unavailable()
            val count = post.optInt("carousel_media_count", sidecar?.optInt("count", -1) ?: -1)
            if (children.length() == 0 || (count >= 0 && children.length() != count)) unavailable()
            (0 until children.length()).map { index ->
                val child = children.optJSONObject(index) ?: unavailable()
                if (carousel != null) child else child.optJSONObject("node") ?: unavailable()
            }
        } else {
            val kind = kind(post) ?: return null
            if (kind == MediaKind.VIDEO && !includeSingleVideo) return null
            listOf(post)
        }
        val title = (post.optJSONObject("user") ?: post.optJSONObject("owner"))?.optString("username").orEmpty()
        return MediaPost(nodes.mapIndexed { i, node -> item(node, "$platform-$code-${i + 1}", referer) }, title)
    }

    private fun kind(node: JSONObject): MediaKind? = when {
        node.optInt("media_type") == 2 || node.optString("__typename").endsWith("Video") ||
            node.optBoolean("is_video") -> MediaKind.VIDEO
        node.optInt("media_type") == 1 || node.optString("__typename").endsWith("Image") ||
            node.opt("is_video") == false -> MediaKind.PHOTO
        else -> null
    }

    private fun item(node: JSONObject, name: String, referer: String): MediaItem {
        val kind = kind(node) ?: unavailable()
        val sources = mutableListOf<MediaSource>()
        fun add(url: String, w: Int, h: Int) {
            val uri = runCatching { URI(url) }.getOrNull() ?: return
            val host = uri.host?.lowercase() ?: return
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 ||
                !(host == "cdninstagram.com" || host.endsWith(".cdninstagram.com") ||
                    host == "fbcdn.net" || host.endsWith(".fbcdn.net"))) return
            val size = if (w > 0 && h > 0) Quality(w, h) else null
            sources += MediaSource(url, quality = size, name = name, referer = referer)
        }

        val variants = if (kind == MediaKind.PHOTO) node.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: node.optJSONArray("display_resources") else node.optJSONArray("video_versions")
        if (variants != null) for (i in 0 until variants.length()) {
            val variant = variants.optJSONObject(i) ?: continue
            add(variant.optString("url", variant.optString("src")),
                variant.optInt("width", variant.optInt("config_width")),
                variant.optInt("height", variant.optInt("config_height")))
        }
        val dim = node.optJSONObject("dimensions")
        add(node.optString(if (kind == MediaKind.PHOTO) "display_url" else "video_url"),
            dim?.optInt("width") ?: 0, dim?.optInt("height") ?: 0)
        if (sources.isEmpty()) unavailable()
        return MediaItem(kind, sources.sortedByDescending { it.quality?.run { width.toLong() * height } ?: 0L }
            .distinctBy { it.uri })
    }

    private fun unavailable(): Nothing = throw MediaFailure(FailureReason.UNAVAILABLE)
}
