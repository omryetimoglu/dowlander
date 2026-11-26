package com.evasi.downloader.media

import java.net.URI

/** Identifies a single post without passing arbitrary hosts, redirects or playlist parameters to the extractor. */
internal object SocialVideoLinks {
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
    private val youtubePath = Regex("/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})")
    private val instagramPost = Regex("/(?:[A-Za-z0-9_.]+/)?(reel|reels|p|tv)/([A-Za-z0-9_-]+)")
    private val threadsPost = Regex("/(?:@[A-Za-z0-9_.]+/post|t)/[A-Za-z0-9_-]+")
    private val instagramShare = Regex("/share/(?:reel/|p/)?[A-Za-z0-9_-]+")
    private val tiktokPost = Regex("/@[A-Za-z0-9_.-]+/(?:video|photo)/[0-9]+")
    private val tiktokLegacy = Regex("/(?:v|share/video)/[0-9]+(?:\\.html)?")
    private val tiktokShort = Regex("/t/[A-Za-z0-9_-]+")
    private val shortCode = Regex("/[A-Za-z0-9_-]+")
    private val facebookVideo = Regex("/(?:reel/[0-9]+|share/[rvp]/[A-Za-z0-9_-]+|[A-Za-z0-9_.-]+/videos/(?:[^/]+/)?[0-9]+)")
    private val facebookPost = Regex("/(?:[A-Za-z0-9_.-]+/posts|groups/[A-Za-z0-9_.-]+/(?:posts|permalink))/(?:[0-9]+|pfbid[A-Za-z0-9]+)")
    private val numericId = Regex("[0-9]+")
    private val facebookStoryId = Regex("(?:[0-9]+|pfbid[A-Za-z0-9]+)")

    fun match(uri: URI): URI? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != (if (scheme == "https") 443 else 80))) return null
        val path = uri.rawPath.orEmpty().removeSuffix("/")
        return when (uri.host?.lowercase()) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> {
                val id = if (path == "/watch") uri.param("v") else youtubePath.matchEntire(path)?.groupValues?.get(1)
                youtube(id)
            }
            "youtu.be" -> youtube(path.removePrefix("/"))
            "instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am" -> {
                val post = instagramPost.matchEntire(path)
                when {
                    instagramShare.matches(path) -> URI("https://www.instagram.com$path/")
                    post != null -> {
                        val (kind, code) = post.destructured
                        URI("https://www.instagram.com/${if (kind == "reels") "reel" else kind}/$code/")
                    }
                    else -> null
                }
            }
            "threads.net", "www.threads.net", "threads.com", "www.threads.com" ->
                if (threadsPost.matches(path)) URI("https://www.threads.com$path") else null
            "tiktok.com", "www.tiktok.com", "m.tiktok.com" -> {
                when {
                    tiktokShort.matches(path) -> URI("https://www.tiktok.com$path/")
                    tiktokPost.matches(path) || tiktokLegacy.matches(path) -> URI("https://www.tiktok.com$path")
                    else -> null
                }
            }
            "vm.tiktok.com", "vt.tiktok.com", "fb.watch" ->
                if (shortCode.matches(path)) URI("https://${uri.host.lowercase()}$path/") else null
            "facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com" ->
                when {
                    path in setOf("/photo", "/photo.php") && uri.param("fbid")?.matches(numericId) == true ->
                        URI("https://www.facebook.com/photo/?fbid=${uri.param("fbid")}")
                    path == "/permalink.php" || path == "/story.php" -> {
                        val page = uri.param("id")?.takeIf(numericId::matches) ?: return null
                        val story = uri.param("story_fbid")?.takeIf(facebookStoryId::matches) ?: return null
                        URI("https://www.facebook.com$path?story_fbid=$story&id=$page")
                    }
                    path in setOf("/watch", "/watch/live", "/video.php", "/photo.php") ->
                        uri.param("v")?.takeIf(numericId::matches)?.let { URI("https://www.facebook.com/watch/?v=$it") }
                    facebookVideo.matches(path) || facebookPost.matches(path) -> URI("https://www.facebook.com$path/")
                    else -> null
                }
            else -> null
        }
    }

    private fun youtube(id: String?): URI? =
        id?.takeIf(youtubeId::matches)?.let { URI("https://www.youtube.com/watch?v=$it") }

    private fun URI.param(name: String) = rawQuery?.split('&')?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}
