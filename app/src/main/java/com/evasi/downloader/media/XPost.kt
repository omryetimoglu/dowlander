package com.evasi.downloader.media

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object XPost {
    private val hosts = setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
    private val postPath = Regex("""/(?:[a-zA-Z0-9_]+/status(?:es)?|i/(?:web/)?status)/([0-9]{1,20})(?:/(?:photo|video)/[0-9]+)?/?""")
    private val dimensions = Regex("""/([1-9][0-9]{0,4})x([1-9][0-9]{0,4})/""")

    private val imagePath = Regex("""/media/([A-Za-z0-9_-]+)(?:\.([A-Za-z0-9]+))?(?::[A-Za-z0-9_]+)?""")
    private val formats = setOf("jpg", "jpeg", "png", "webp")

    fun statusId(uri: URI): String? {
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.lowercase() !in hosts ||
            uri.rawUserInfo != null || uri.port != -1) return null
        return postPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }

    fun endpoint(id: String) = URI("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${syndicationToken(id)}")

    fun parse(body: String): MediaPost {
        val tweet = JSONObject(body)
        if (tweet.isNull("id_str") || tweet.optString("__typename") == "TweetTombstone") {
            throw MediaFailure(FailureReason.UNAVAILABLE)
        }
        return MediaPost(mediaItems(tweet), tweet.optJSONObject("user")?.optString("name").orEmpty().take(100))
    }

    private fun mediaItems(tweet: JSONObject): List<MediaItem> {
        val id = tweet.getString("id_str")
        val details = tweet.optJSONArray("mediaDetails")
        val items = mutableListOf<MediaItem>()
        if (details != null) {
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                val name = "X-$id-${index + 1}"
                if (detail.optString("type") == "photo") {
                    items += photoItem(detail.optString("media_url_https"), detail.optJSONObject("original_info"), name)
                    continue
                }
                val kind = when (detail.optString("type")) {
                    "video" -> MediaKind.VIDEO
                    "animated_gif" -> MediaKind.GIF
                    else -> continue
                }
                val variants = detail.optJSONObject("video_info")?.optJSONArray("variants")
                items += videoItem(kind, variants, name, "url", "content_type")
            }
        }
        if (items.isEmpty()) {
            // Some embeds expose only video.variants, with src/type instead of url/content_type.
            val video = tweet.optJSONObject("video")
            if (video != null) {
                items += videoItem(MediaKind.VIDEO, video.optJSONArray("variants"), "X-$id-1", "src", "type")
            } else {
                val photos = tweet.optJSONArray("photos") ?: return emptyList()
                for (index in 0 until photos.length()) {
                    val photo = photos.getJSONObject(index)
                    items += photoItem(photo.optString("url"), photo, "X-$id-${index + 1}")
                }
            }
        }
        return items
    }

    private fun photoItem(url: String, metadata: JSONObject?, name: String): MediaItem {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw MediaFailure(FailureReason.UNSUPPORTED)
        if (!MediaCdn.X_PHOTO.accepts(uri)) throw MediaFailure(FailureReason.UNSUPPORTED)
        val path = imagePath.matchEntire(uri.rawPath.orEmpty()) ?: throw MediaFailure(FailureReason.UNSUPPORTED)
        val format = path.groupValues[2].ifEmpty {
            uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("format=") }?.substringAfter('=').orEmpty()
        }.lowercase()
        if (format.isNotEmpty() && format !in formats) throw MediaFailure(FailureReason.UNSUPPORTED)
        val query = if (format.isEmpty()) "name=orig" else "format=$format&name=orig"
        val original = "https://pbs.twimg.com/media/${path.groupValues[1]}?$query"
        val width = metadata?.optInt("width") ?: 0
        val height = metadata?.optInt("height") ?: 0
        val size = if (width > 0 && height > 0) Quality(width, height) else null
        return MediaItem(MediaKind.PHOTO, listOf(MediaSource(original, quality = size, name = name, deliveryCdn = MediaCdn.X_PHOTO)))
    }

    private fun videoItem(kind: MediaKind, variants: JSONArray?, name: String, urlKey: String, typeKey: String): MediaItem {
        val sources = (0 until (variants?.length() ?: 0)).mapNotNull { index ->
            val variant = variants?.optJSONObject(index) ?: return@mapNotNull null
            if (variant.optString(typeKey) != "video/mp4") return@mapNotNull null
            val url = variant.optString(urlKey)
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (!MediaCdn.X_VIDEO.accepts(uri)) return@mapNotNull null
            val size = dimensions.find(uri.path.orEmpty())?.destructured?.let { (width, height) ->
                Quality(width.toInt(), height.toInt())
            }
            MediaSource(url, "video/mp4", size, name, deliveryCdn = MediaCdn.X_VIDEO) to variant.optLong("bitrate")
        }.sortedWith(compareByDescending<Pair<MediaSource, Long>> { it.second }
            .thenByDescending { it.first.quality?.let { size -> size.width.toLong() * size.height } ?: 0L })
            .map { it.first }.distinctBy { it.uri }
        if (sources.isEmpty()) throw MediaFailure(FailureReason.UNSUPPORTED)
        return MediaItem(kind, sources)
    }
}
