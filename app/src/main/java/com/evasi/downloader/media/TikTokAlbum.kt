package com.evasi.downloader.media

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

internal object TikTokAlbum {
    private val postPath = Regex("/@[A-Za-z0-9_.-]+/(photo|video)/([0-9]+)/?")
    private val shortPath = Regex("/t/[A-Za-z0-9_-]+/?")
    private val script = Regex("""<script\b[^>]*?\sid\s*=\s*["'](__UNIVERSAL_DATA_FOR_REHYDRATION__|SIGI_STATE|sigi-persisted-data)["'][^>]*>""",
        RegexOption.IGNORE_CASE)

    fun isCandidate(uri: URI): Boolean = when (uri.host) {
        "vm.tiktok.com", "vt.tiktok.com" -> true
        "www.tiktok.com" -> postPath.matchEntire(uri.path)?.groupValues?.get(1) == "photo" || shortPath.matches(uri.path)
        else -> false
    }

    fun isVideoCandidate(uri: URI) =
        uri.host == "www.tiktok.com" && postPath.matchEntire(uri.path)?.groupValues?.get(1) == "video"

    // The /video/ page exposes webapp.video-detail for photo posts too.
    fun videoPage(uri: URI): URI = if (uri.host == "www.tiktok.com" && postPath.matches(uri.path))
        URI("https://www.tiktok.com" + uri.path.replace("/photo/", "/video/"))
    else uri

    /** fetchPage returns the final URL after redirects and its public HTML. */
    fun probe(uri: URI, fetchPage: (URI) -> Pair<URI, String>): MediaPost? {
        val input = SocialVideoLinks.match(uri) ?: return null
        if (!isCandidate(input) && !isVideoCandidate(input)) return null
        val requested = videoPage(input)
        val (finalUrl, html) = fetchPage(requested)
        if (!finalUrl.scheme.equals("https", true) || finalUrl.rawUserInfo != null || finalUrl.port !in setOf(-1, 443) ||
            finalUrl.host?.lowercase() !in setOf("www.tiktok.com", "tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com")) {
            if (input.path.contains("/photo/")) throw MediaFailure(FailureReason.UNAVAILABLE)
            return null
        }
        val state = PageState(html)
        // Some shortened links return a shell with a canonical post in seo.abtest.
        val post = canonicalPost(finalUrl) ?: state.canonical?.let(::canonicalPost)
        if (post == null) {
            if (input.path.contains("/photo/")) throw MediaFailure(FailureReason.UNAVAILABLE)
            return null
        }
        val id = post.path.trimEnd('/').substringAfterLast('/')
        val expected = postPath.matchEntire(input.path)?.groupValues?.get(2)
        if (expected != null && expected != id) throw MediaFailure(FailureReason.UNAVAILABLE)
        val expectPhoto = post.path.contains("/photo/") || input.path.contains("/photo/")
        state.item(id, expectPhoto)?.let { return album(it, id, expectPhoto) }
        val detail = videoPage(post)
        if (detail != requested && detail != SocialVideoLinks.match(finalUrl)) {
            val (detailUrl, detailHtml) = fetchPage(detail)
            if (canonicalPost(detailUrl)?.path?.trimEnd('/')?.substringAfterLast('/') != id)
                throw MediaFailure(FailureReason.UNAVAILABLE)
            PageState(detailHtml).item(id, expectPhoto)?.let { return album(it, id, expectPhoto) }
        }
        if (expectPhoto) throw MediaFailure(FailureReason.UNAVAILABLE)
        return null
    }

    private fun canonicalPost(uri: URI) = SocialVideoLinks.match(uri)?.takeIf {
        it.host == "www.tiktok.com" && postPath.matches(it.path)
    }

    fun parse(html: String, id: String): MediaPost? = PageState(html).item(id)?.let { album(it, id) }

    private class PageState(html: String) {
        private var scope: JSONObject? = null
        private var modules: JSONObject? = null
        init {
            for (tag in script.findAll(html)) {
                val start = tag.range.last + 1
                val end = html.indexOf("</script>", start, ignoreCase = true)
                if (end < 0) continue
                val data = try { JSONObject(html.substring(start, end)) } catch (_: JSONException) { continue }
                if (tag.groupValues[1] == "__UNIVERSAL_DATA_FOR_REHYDRATION__") scope = data.optJSONObject("__DEFAULT_SCOPE__")
                else modules = data.optJSONObject("ItemModule")
            }
        }

        val canonical: URI? get() = scope?.optJSONObject("seo.abtest")?.optString("canonical")
            ?.let { runCatching { URI(it) }.getOrNull() }

        fun item(id: String, expectPhoto: Boolean = false): JSONObject? {
            val detail = scope?.optJSONObject("webapp.video-detail")
            if (detail != null && detail.optInt("statusCode", 0) != 0) {
                if (expectPhoto) throw MediaFailure(FailureReason.UNAVAILABLE)
                return null
            }
            val item = detail?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            return item?.takeIf { it.optString("id") == id } ?: modules?.optJSONObject(id)?.takeIf { it.optString("id", id) == id }
        }
    }

    private fun album(item: JSONObject, id: String, expectPhoto: Boolean = false): MediaPost? {
        if (item.optBoolean("privateItem")) throw MediaFailure(FailureReason.PRIVATE)
        if (item.isNull("imagePost")) {
            val video = item.optJSONObject("video")
            if (expectPhoto && (video == null || listOf("playAddr", "downloadAddr", "bitrateInfo").none { !video.isNull(it) }))
                throw MediaFailure(FailureReason.UNAVAILABLE)
            return null
        }
        val album = item.optJSONObject("imagePost") ?: throw MediaFailure(FailureReason.UNAVAILABLE)
        val images = album.optJSONArray("images") ?: throw MediaFailure(FailureReason.UNAVAILABLE)
        if (images.length() == 0) throw MediaFailure(FailureReason.UNAVAILABLE)
        val items = (0 until images.length()).map { index ->
            val image = images.optJSONObject(index) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            val urls = image.optJSONObject("imageURL")?.optJSONArray("urlList") ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            val url = (0 until urls.length()).asSequence().map { urls.optString(it) }
                .firstOrNull(::isImageUrl) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            val width = image.optInt("imageWidth")
            val height = image.optInt("imageHeight")
            val quality = if (width > 0 && height > 0) Quality(width, height) else null
            MediaItem(MediaKind.PHOTO, listOf(MediaSource(url, quality = quality, name = "TikTok-$id-${index + 1}",
                referer = "https://www.tiktok.com/", deliveryCdn = MediaCdn.TIKTOK)))
        }
        return MediaPost(items, item.caption("author") ?: "TikTok", description = item.caption("desc"),
            previewUrl = items.firstOrNull()?.sources?.firstOrNull()?.uri)
    }

    private fun isImageUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return MediaCdn.TIKTOK.accepts(uri)
    }
}
