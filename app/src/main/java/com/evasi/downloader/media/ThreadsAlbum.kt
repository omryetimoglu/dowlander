package com.evasi.downloader.media

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

internal object ThreadsAlbum {
    private val script = Regex("""<script\b[^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun endpoint(link: URI): URI? = SocialVideoLinks.match(link)?.takeIf { it.host == "www.threads.com" }

    fun parse(body: String, page: URI): MediaPost? {
        val code = endpoint(page)?.path?.substringAfterLast('/') ?: return null
        for (tag in script.findAll(body)) {
            val json = tag.groupValues[1].trim()
            if (!json.startsWith('{') || !json.contains("\"thread_items\"")) continue
            val root = try { JSONObject(json) } catch (_: JSONException) { continue }
            val post = findPost(root, code, 0) ?: continue
            if (post.optJSONObject("user")?.optBoolean("is_private") == true) {
                throw MediaFailure(FailureReason.UNAVAILABLE)
            }
            return MetaPostMedia.parse(post, code, "Threads", "https://www.threads.com/", includeSingleVideo = true)
        }
        return null
    }

    // The public Relay response can also contain replies, suggested threads and quoted posts.
    // Only a direct thread item's post matching the requested code owns the downloaded media.
    private fun findPost(value: Any?, code: String, depth: Int): JSONObject? {
        if (depth > 50) return null
        when (value) {
            is JSONObject -> {
                val items = value.optJSONArray("thread_items")
                if (items != null) for (i in 0 until items.length()) {
                    val post = items.optJSONObject(i)?.optJSONObject("post") ?: continue
                    if (post.optString("code") == code) return post
                }
                for (key in value.keys()) {
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) findPost(child, code, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) findPost(value.opt(i), code, depth + 1)?.let { return it }
        }
        return null
    }
}
