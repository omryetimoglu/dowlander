package com.evasi.downloader.media

import org.json.JSONObject
import java.net.URI
import kotlin.math.abs

internal object Mastodon {
    private val postPath = Regex("""/(?:web/)?(?:@[^/]+/|(?:ap/)?users/[^/]+/statuses/|statuses/)([0-9]+)(?:/(?:media|references)/[0-9]+)?/?""")

    fun endpoint(link: String): URI {
        val uri = runCatching { URI(link) }.getOrNull() ?: throw MediaFailure(FailureReason.INVALID)
        val host = uri.host ?: throw MediaFailure(FailureReason.INVALID)
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.userInfo != null) {
            throw MediaFailure(FailureReason.UNSUPPORTED)
        }
        val id = postPath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
            ?: throw MediaFailure(FailureReason.UNSUPPORTED)
        return URI("https", null, host, uri.port.takeUnless { it == 80 } ?: -1, "/api/v1/statuses/$id", null, null)
    }

    fun parse(body: String): MediaPost {
        val response = JSONObject(body)
        val status = response.optJSONObject("reblog") ?: response
        if (status.optString("visibility") !in setOf("public", "unlisted")) throw MediaFailure(FailureReason.UNAVAILABLE)
        val attachments = status.optJSONArray("media_attachments") ?: throw MediaFailure(FailureReason.UNSUPPORTED)
        val items = (0 until attachments.length()).mapNotNull { index ->
            val attachment = attachments.getJSONObject(index)
            val type = attachment.optString("type")
            if (type !in setOf("image", "video", "gifv")) return@mapNotNull null
            val original = attachment.httpsUrl("url") ?: attachment.httpsUrl("remote_url")
                ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            val kind = when {
                type == "gifv" || URI(original).path.orEmpty().endsWith(".gif", true) -> MediaKind.GIF
                type == "image" -> MediaKind.PHOTO
                else -> MediaKind.VIDEO
            }
            val meta = attachment.optJSONObject("meta")
            val size = (meta?.optJSONObject("original") ?: meta).dimensions()
            val name = "Mastodon-" + status.optString("id") + "-" + (index + 1)
            val sources = mutableListOf(MediaSource(original, quality = size, name = name))
            // Video previews are still frames. Only offer resized versions of static
            // photos, and only when the server reports an uncropped, smaller image.
            if (kind == MediaKind.PHOTO && size != null && URI(original).path.orEmpty().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png")) {
                val small = meta?.optJSONObject("small").dimensions()
                val preview = attachment.httpsUrl("preview_url")
                if (preview != null && preview != original && small != null && small.width < size.width &&
                    abs(small.width.toDouble() / small.height - size.width.toDouble() / size.height) < 0.01) {
                    sources.add(MediaSource(preview, quality = small, name = name))
                }
            }
            MediaItem(kind, sources)
        }
        val account = status.optJSONObject("account")
        val displayName = account?.optString("display_name").orEmpty().ifBlank { account?.optString("acct").orEmpty() }
        val content = status.caption("content")?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        val poster = attachments.optJSONObject(0)?.httpsUrl("preview_url")
        return MediaPost(items, displayName.take(100), content, poster)
    }

    private fun JSONObject.httpsUrl(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key)
        return value.takeIf { runCatching { URI(it) }.getOrNull()?.let { uri ->
            uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null
        } == true }
    }

    private fun JSONObject?.dimensions(): Quality? {
        if (this == null) return null
        val width = optInt("width")
        val height = optInt("height")
        return if (width > 0 && height > 0) Quality(width, height) else null
    }
}
