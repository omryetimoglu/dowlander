package com.evasi.downloader.media

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONException
import java.net.URI

/** Reads the post's own ordered Relay attachments. Feed and comment images are unrelated. */
internal object FacebookAlbum {
    private val hosts = setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com")
    private val numeric = Regex("[0-9]+")
    private val postPath = Regex("/(?:[A-Za-z0-9_.-]+/posts|groups/[A-Za-z0-9_.-]+/(?:posts|permalink))/([0-9]+|pfbid[A-Za-z0-9]+)/?")
    private val scripts = Regex("""<script\b[^>]*>([\s\S]*?)</script\s*>""", RegexOption.IGNORE_CASE)
    private val canonicalTag = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""\b(rel|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun endpoint(link: URI): URI? {
        val normalized = SocialVideoLinks.match(link) ?: return null
        if (normalized.host !in hosts) return null
        return normalized.takeIf { postId(it) != null || photoId(it) != null || it.path.startsWith("/share/p/") }
    }

    fun parse(body: String, finalPage: URI, fetchPhotoPage: (URI) -> String): MediaPost? {
        if (!facebookPage(finalPage)) return null
        val canonical = canonicalPage(body)
        val photo = photoId(finalPage) ?: canonical?.let(::photoId)
        if (photo != null) {
            val item = viewer(body, photo) ?: throw MediaFailure(FailureReason.UNAVAILABLE)
            return MediaPost(listOf(item), "Facebook")
        }
        val ids = setOfNotNull(postId(finalPage), canonical?.let(::postId))
        if (ids.isEmpty()) return null
        for (node in objects(body)) {
            val story = node.optJSONObject("comet_sections")?.optJSONObject("content")?.optJSONObject("story") ?: node
            val attachments = story.optJSONArray("attachments") ?: continue
            if (!matchesPost(node, ids) && !matchesPost(story, ids)) continue
            val media = albumMedia(attachments) ?: continue
            val items = media.map { item ->
                val id = item.optString("id").takeIf(numeric::matches) ?: unavailable()
                when (type(item)) {
                    "Photo" -> fullPhoto(item, id, viewer = false) ?: run {
                        val page = URI("https://www.facebook.com/photo/?fbid=$id")
                        viewer(fetchPhotoPage(page), id) ?: unavailable()
                    }
                    "Video" -> MediaItem(MediaKind.VIDEO, listOf(MediaSource(
                        "https://www.facebook.com/watch/?v=$id", name = "Facebook-$id.mp4", platformVideo = true,
                    )))
                    else -> unavailable()
                }
            }
            return MediaPost(items, story.optJSONObject("message")?.optString("text").orEmpty().take(100))
        }
        return null
    }

    private fun albumMedia(attachments: JSONArray): List<JSONObject>? {
        val media = mutableListOf<JSONObject>()
        var incomplete = false
        for (index in 0 until attachments.length()) {
            val raw = attachments.optJSONObject(index)
            if (raw == null) {
                incomplete = true
                continue
            }
            val attachment = (raw.optJSONObject("style_type_renderer") ?: raw.optJSONObject("styles"))
                ?.optJSONObject("attachment") ?: raw
            val all = attachment.optJSONObject("all_subattachments")
            val grid = attachment.optJSONObject("five_photos_subattachments")
            val allMedia = all?.let(::connectionMedia)
            if (!allMedia.isNullOrEmpty()) {
                if (all.optInt("count", allMedia.size) != allMedia.size ||
                    all.optJSONObject("page_info")?.optBoolean("has_next_page") == true) incomplete = true
                media += allMedia
            } else if (grid != null) {
                // This field is a capped grid. A page of five nodes by itself proves nothing about the total.
                val cells = connectionMedia(grid) ?: unavailable()
                val total = all?.optInt("count", -1) ?: -1
                val gridCount = grid.optInt("count", -1)
                val cursor = grid.optJSONObject("page_info")
                val atEnd = cursor?.opt("has_next_page") == false
                val confirmed = total == cells.size || (gridCount == cells.size && atEnd)
                if (cells.isEmpty() || !confirmed || (total >= 0 && total != cells.size) ||
                    (gridCount >= 0 && gridCount != cells.size) || cursor?.optBoolean("has_next_page") == true ||
                    all?.optJSONObject("page_info")?.optBoolean("has_next_page") == true) unavailable()
                media += cells
            } else {
                if (all != null) incomplete = true
                // A non-media attachment can accompany a link-only post. For a mixed album,
                // keep its place so the caller can reject an incomplete result.
                media += attachment.optJSONObject("media") ?: JSONObject()
            }
        }
        if (media.none { type(it) == "Photo" }) return null
        if (incomplete || media.map { it.optString("id") }.distinct().size != media.size) unavailable()
        return media
    }

    private fun connectionMedia(connection: JSONObject): List<JSONObject>? {
        val nodes = connection.optJSONArray("nodes")
        val rows = nodes ?: connection.optJSONArray("edges") ?: return null
        return (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index)
            val node = if (nodes != null) row else row?.optJSONObject("node")
            node?.optJSONObject("media") ?: JSONObject()
        }
    }

    private fun viewer(body: String, id: String): MediaItem? {
        for (obj in objects(body)) {
            if (obj.optString("id") == id && type(obj) == "Photo") {
                fullPhoto(obj, id, viewer = true)?.let { return it }
            }
        }
        return null
    }

    private fun fullPhoto(obj: JSONObject, id: String, viewer: Boolean): MediaItem? {
        // image.uri on the post grid is a thumbnail. Only accept this key on the dedicated photo page.
        val keys = if (viewer) listOf("photo_image", "viewer_image", "image") else listOf("photo_image", "viewer_image")
        for (key in keys) {
            val image = obj.optJSONObject(key) ?: continue
            val uri = runCatching { URI(image.optString("uri")) }.getOrNull() ?: continue
            val host = uri.host?.lowercase() ?: continue
            if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
                !(host.endsWith(".fbcdn.net") || host.endsWith(".fbsbx.com"))) continue
            val w = image.optInt("width")
            val h = image.optInt("height")
            val size = if (w > 0 && h > 0) Quality(w, h) else null
            return MediaItem(MediaKind.PHOTO, listOf(MediaSource(uri.toString(), quality = size,
                name = "Facebook-$id", referer = "https://www.facebook.com/")))
        }
        return null
    }

    private fun objects(body: String): Sequence<JSONObject> = sequence {
        for (script in scripts.findAll(body)) {
            val text = script.groupValues[1].trim()
            if (!text.startsWith('{') && !text.startsWith('[')) continue
            val root = try { JSONTokener(text).nextValue() } catch (_: JSONException) { continue }
            val stack = ArrayDeque<Any>()
            if (root is JSONObject || root is JSONArray) stack.addLast(root)
            while (stack.isNotEmpty()) {
                when (val value = stack.removeLast()) {
                    is JSONObject -> {
                        yield(value)
                        for (key in value.keys()) {
                            val child = value.opt(key)
                            if (child is JSONObject || child is JSONArray) stack.addLast(child)
                        }
                    }
                    is JSONArray -> for (i in value.length() - 1 downTo 0) {
                        val child = value.opt(i)
                        if (child is JSONObject || child is JSONArray) stack.addLast(child)
                    }
                }
            }
        }
    }

    private fun type(obj: JSONObject): String =
        listOf("__typename", "__isMedia", "__isNode").map { obj.optString(it) }
            .firstOrNull { it == "Photo" || it == "Video" }.orEmpty()

    private fun matchesPost(obj: JSONObject, ids: Set<String>) =
        obj.optString("post_id") in ids || obj.optString("id") in ids ||
            listOf("url", "permalink_url").any { key ->
                val uri = runCatching { URI(obj.optString(key)) }.getOrNull()
                uri != null && facebookPage(uri) && postId(uri) in ids
            }

    private fun canonicalPage(body: String): URI? = canonicalTag.findAll(body).mapNotNull { tag ->
        val attrs = attribute.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        if (!attrs["rel"].equals("canonical", true)) return@mapNotNull null
        attrs["href"]?.let { runCatching { URI(it.replace("&amp;", "&")) }.getOrNull() }?.takeIf(::facebookPage)
    }.firstOrNull()

    private fun facebookPage(uri: URI) = uri.scheme == "https" && uri.host?.lowercase() in hosts &&
        uri.rawUserInfo == null && uri.port in setOf(-1, 443)

    private fun postId(uri: URI): String? =
        postPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: when (uri.path) {
            "/story.php", "/permalink.php" -> param(uri, "story_fbid")
            else -> null
        }

    private fun photoId(uri: URI) = if (uri.path?.trimEnd('/') in setOf("/photo", "/photo.php")) {
        param(uri, "fbid")?.takeIf(numeric::matches)
    } else null

    private fun param(uri: URI, key: String) =
        uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun unavailable(): Nothing = throw MediaFailure(FailureReason.UNAVAILABLE)
}
