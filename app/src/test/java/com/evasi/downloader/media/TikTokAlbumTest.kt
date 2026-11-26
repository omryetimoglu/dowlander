package com.evasi.downloader.media

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class TikTokAlbumTest {
    private val id = "7240568259186019630"
    private val photo = URI("https://www.tiktok.com/@chillezy/photo/$id")
    private val video = URI("https://www.tiktok.com/@chillezy/video/$id")
    private val short = URI("https://vm.tiktok.com/Abc123/")
    private val first = "https://p16-sign-va.tiktokcdn.com/image-1.jpeg?sign=a"
    private val second = "https://p19-common-sign-useast.tiktokcdn-us.com/image-2.jpeg?sign=b"
    private val item = """{"id":"$id","desc":"Photo mode","video":{"cover":"https://example.com/poster"},
        "imagePost":{"images":[{"imageWidth":1280,"imageHeight":720,"imageURL":{"urlList":["http://localhost/a","$first"]}},
        {"imageWidth":640,"imageHeight":480,"imageURL":{"urlList":["$second"]}}]}}"""

    private fun page(post: String) = """<script type="application/json" id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"statusCode":0,"itemInfo":{"itemStruct":$post}}}}</script>"""

    @Test fun photoPageIsReadFromItsVideoDetailAndPreservesEverySlide() {
        val result = requireNotNull(TikTokAlbum.probe(photo) { url ->
            assertEquals(video, url)
            url to page(item)
        })
        assertEquals("Photo mode", result.title)
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.PHOTO), result.items.map { it.kind })
        assertEquals(listOf(first, second), result.items.map { it.sources.single().uri })
        assertTrue(result.items.all { it.sources.single().referer == "https://www.tiktok.com/" })
        assertEquals(listOf(Quality(1280, 720), Quality(640, 480)), result.items.map { it.sources.single().quality })
        assertEquals(listOf("TikTok-$id-1", "TikTok-$id-2"), result.items.map { it.sources.single().name })
    }

    @Test fun shortShareThatRedirectsToPhotoShellFetchesDetailOnce() {
        val shortShell = "<html>Public TikTok post</html>"
        val seen = mutableListOf<URI>()
        val result = TikTokAlbum.probe(short) { url ->
            seen += url
            if (url == short) photo to shortShell else video to page(item)
        }
        assertEquals(listOf(short, video), seen)
        assertEquals(2, result?.items?.size)
        assertNull(TikTokAlbum.probe(short) { video to page("""{"id":"$id","video":{"id":"video"}}""") })
        assertNull(TikTokAlbum.probe(short) { URI("https://www.tiktok.com/login") to "Sign in" })
    }

    @Test fun sigiSelectsOnlyTheRequestedItemAndOnlyPublicCdnImages() {
        val html = """<script id='SIGI_STATE'>{"ItemModule":{"1":{"id":"1","desc":"other"},"$id":$item}}</script>"""
        assertEquals(2, TikTokAlbum.parse(html, id)?.items?.size)
        assertNull(TikTokAlbum.parse(html, "2"))
        assertEquals(FailureReason.UNAVAILABLE, assertThrows(MediaFailure::class.java) {
            TikTokAlbum.parse(html.replace(second, "https://p19.tiktokcdn.com.attacker.example/image"), id)
        }.reason)
    }

    @Test fun missingDeniedOrMismatchedPhotoCannotFallThroughToVideo() {
        for (response in listOf(
            video to "<html>Sign in</html>",
            video to page("""{"id":"$id","video":{"cover":"https://example.com/poster"}}"""),
            URI("https://www.tiktok.com/login") to "<html>Sign in</html>",
            URI("https://www.tiktok.com/@other/video/99") to page(item),
            video to page(item.replace("\"imagePost\":", "\"privateItem\":true,\"imagePost\":")),
            video to """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">{"__DEFAULT_SCOPE__":
                {"webapp.video-detail":{"statusCode":10216}}}</script>""",
        )) assertEquals(FailureReason.UNAVAILABLE, assertThrows(MediaFailure::class.java) {
            TikTokAlbum.probe(photo) { response }
        }.reason)
    }
}
