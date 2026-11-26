package com.evasi.downloader.media

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI

/** Instagram's public post page carries both the photos and the progressive video files of a carousel. */
internal object InstagramAlbum {
    private val script = Regex("""<script\b[^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun endpoint(link: URI): URI? {
        val canonical = SocialVideoLinks.match(link) ?: return null
        if (canonical.host != "www.instagram.com") return null
        if (canonical.path.startsWith("/share/")) return canonical
        val code = canonical.path.trimEnd('/').substringAfterLast('/')
        return URI("https://www.instagram.com/p/$code/")
    }

    /** A single video can use the video extractor's DASH support. An identified album must be complete. */
    fun parse(body: String, page: URI): MediaPost? {
        val postPage = endpoint(page) ?: return null
        if (postPage.path.startsWith("/share/")) return null
        val code = postPage.path.trimEnd('/').substringAfterLast('/')
        for (match in script.findAll(body)) {
            val payload = match.groupValues[1].trim()
            if (!payload.contains("\"xig_polaris_media\"") && !payload.contains("\"shortcode_media\"") &&
                !payload.contains("\"xdt_shortcode_media\"")) continue
            val json = if (payload.startsWith("window._sharedData")) payload.substringAfter('=').trim().trimEnd(';')
                else payload
            if (!json.startsWith('{')) continue
            val root = try { JSONObject(json) } catch (_: JSONException) { continue }
            val post = findPost(root, code) ?: continue
            return MetaPostMedia.parse(post, code, "Instagram", "https://www.instagram.com/", includeSingleVideo = false)
        }
        return null
    }

    // Relay nests the main post below server-side rendering commands. Match only the requested
    // shortcode (or its numeric media ID) to exclude suggested posts in the same document.
    private fun findPost(value: Any?, code: String, depth: Int = 0): JSONObject? {
        if (depth > 50) return null
        when (value) {
            is JSONObject -> {
                for (key in value.keys()) {
                    val child = value.opt(key)
                    val post = when (key) {
                        "xig_polaris_media" -> (child as? JSONObject)?.optJSONObject("if_not_gated_logged_out")
                        "shortcode_media", "xdt_shortcode_media" -> child as? JSONObject
                        else -> null
                    }
                    if (post != null && matchesCode(post, code)) return post
                    if (child is JSONObject || child is JSONArray) findPost(child, code, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) findPost(value.opt(i), code, depth + 1)?.let { return it }
        }
        return null
    }

    private fun matchesCode(post: JSONObject, code: String): Boolean {
        val provided = post.optString("code").ifEmpty { post.optString("shortcode") }
        if (provided.isNotEmpty()) return provided == code
        if (code.length > 16 || post.isNull("pk")) return false
        val id = code.fold(BigInteger.ZERO) { n, ch -> n.shiftLeft(6).add(BigInteger.valueOf(alphabet.indexOf(ch).toLong())) }
        return id.toString() == post.optString("pk")
    }

}
