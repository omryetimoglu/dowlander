package com.evasi.downloader.media

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class InstagramAlbumTest {
    private val page = URI("https://www.instagram.com/p/B_2/")
    private val photo = """{"media_type":1,"image_versions2":{"candidates":[
        {"width":320,"height":240,"url":"https://scontent.cdninstagram.com/small.jpg"},
        {"width":1080,"height":810,"url":"https://instagram.fabc1-1.fna.fbcdn.net/full.jpg?oh=signed&oe=next"}]}}"""
    private val video = """{"media_type":2,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/poster.jpg"}]},
        "video_versions":[{"width":720,"height":1280,"url":"https://scontent.cdninstagram.com/video.mp4"}]}"""

    private fun relay(product: String) = """<html><script type="application/json" data-sjs>
        {"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"require":[["RelayPrefetchedStreamCache","next",null,
        ["post",{"__bbox":{"result":{"data":{"xig_polaris_media":{"if_not_gated_logged_out":$product}}}}}]] ]}}]]]}
        </script></html>"""

    @Test fun canonicalizesDirectPostsAndKeepsOpaqueShareLinksForRedirectResolution() {
        assertEquals(page, InstagramAlbum.endpoint(URI("https://instagram.com/public.account/p/B_2/?img_index=2")))
        val share = URI("https://www.instagram.com/share/p/BcD/")
        assertEquals(share, InstagramAlbum.endpoint(share))
        assertNull(InstagramAlbum.endpoint(URI("https://www.instagram.com.example/p/B_2/")))
        assertNull(InstagramAlbum.parse(relay(photo), share))
    }

    @Test fun standalonePhotoUsesLargestPublishedSizeAndPreservesSignedUrl() {
        val standalone = photo.replaceFirst("{", """{"code":"B_2","user":{"username":"author"},""")
        val post = requireNotNull(InstagramAlbum.parse(relay(standalone), page))
        assertEquals("author", post.title)
        val item = post.items.single()
        assertEquals(MediaKind.PHOTO, item.kind)
        assertEquals(listOf(Quality(1080, 810), Quality(320, 240)), item.sources.map { it.quality })
        assertEquals("https://instagram.fabc1-1.fna.fbcdn.net/full.jpg?oh=signed&oe=next", item.sources.first().uri)
        assertEquals("https://www.instagram.com/", item.sources.first().referer)
    }

    @Test fun preservesCarouselOrderAndRepeatedItemsWithoutUsingVideoPosters() {
        val post = requireNotNull(InstagramAlbum.parse(relay("""{"media_type":8,"code":"B_2",
            "carousel_media_count":3,"carousel_media":[$video,$photo,$video]}"""), page))
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.PHOTO, MediaKind.VIDEO), post.items.map { it.kind })
        assertEquals("https://scontent.cdninstagram.com/video.mp4", post.items.first().sources.single().uri)
        assertTrue(post.qualities.isEmpty())
    }

    @Test fun doesNotPublishPartialAlbumsAndIgnoresGatedOrUnrelatedPosts() {
        val album = """{"code":"B_2","media_type":8,"carousel_media":[$photo,{"media_type":2,"video_versions":[]}]}"""
        val missing = assertThrows(MediaFailure::class.java) { InstagramAlbum.parse(relay(album), page) }
        assertEquals(FailureReason.UNAVAILABLE, missing.reason)
        assertThrows(MediaFailure::class.java) {
            InstagramAlbum.parse(relay("""{"code":"B_2","media_type":8,"carousel_media_count":2,"carousel_media":[$photo]}"""), page)
        }
        assertNull(InstagramAlbum.parse(relay(album.replace("B_2", "other")), page))
        assertNull(InstagramAlbum.parse(relay("null"), page))
        assertNull(InstagramAlbum.parse(relay(video.replaceFirst("{", """{"code":"B_2",""")), page))
    }

    @Test fun legacyGraphSidecarUsesChildMediaAndSkipsNonCdnUrls() {
        val post = requireNotNull(InstagramAlbum.parse("""<script>window._sharedData =
            {"entry_data":{"PostPage":[{"graphql":{"shortcode_media":{"shortcode":"B_2","__typename":"GraphSidecar",
            "edge_sidecar_to_children":{"edges":[{"node":{"__typename":"GraphImage",
              "display_url":"https://scontent.cdninstagram.com/1.jpg","dimensions":{"width":1440,"height":1080},
              "display_resources":[{"src":"https://scontent.cdninstagram.com.evil.example/large","config_width":5000,"config_height":5000}]}},
              {"node":{"__typename":"GraphVideo","display_url":"https://scontent.cdninstagram.com/poster.jpg",
              "video_url":"https://video.xx.fbcdn.net/2.mp4"}}]}}}}]}};</script>""", page))
        assertEquals(listOf("https://scontent.cdninstagram.com/1.jpg", "https://video.xx.fbcdn.net/2.mp4"),
            post.items.map { it.sources.single().uri })
    }
}
