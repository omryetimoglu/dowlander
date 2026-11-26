package com.evasi.downloader.media

import org.junit.Assert.*
import org.junit.Test

class MastodonResolverTest {
    @Test fun canonicalAndFederatedPostAddressesResolveOnTheirServingInstance() {
        val endpoint = "https://social.example/api/v1/statuses/123"
        for (url in listOf("https://social.example/@author/123?ref=share",
            "https://social.example/@author@other.example/123", "https://social.example/users/author/statuses/123",
            "https://social.example/web/statuses/123")) {
            assertEquals(endpoint, Mastodon.endpoint(url).toString())
        }
    }

    @Test fun mixedAlbumKeepsOriginalOrderAndNeverOffersAVideoPosterAsAQuality() {
        val post = Mastodon.parse("""
            {"id":"123","visibility":"public","account":{"display_name":"Example"},
             "media_attachments":[
               {"type":"image","url":"https://cdn.example/full.jpg","preview_url":"https://cdn.example/small.jpg",
                "meta":{"original":{"width":1280,"height":720},"small":{"width":640,"height":360}}},
               {"type":"video","url":"https://cdn.example/full.mp4","preview_url":"https://cdn.example/poster.jpg",
                "meta":{"original":{"width":1280,"height":720},"small":{"width":640,"height":360}}},
               {"type":"gifv","url":null,"remote_url":"https://remote.example/loop.mp4","preview_url":"https://cdn.example/frame.png",
                "meta":{"original":{"width":1280,"height":720}}}]}
        """.trimIndent())
        assertEquals("Example", post.title)
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.VIDEO, MediaKind.GIF), post.items.map { it.kind })
        assertEquals(listOf("https://cdn.example/full.jpg", "https://cdn.example/small.jpg"), post.items[0].sources.map { it.uri })
        assertEquals("https://cdn.example/full.mp4", post.items[1].sources.single().uri)
        assertEquals("https://remote.example/loop.mp4", post.items[2].sources.single().uri)
        assertEquals(listOf(Quality(1280, 720)), post.qualities)
    }
}
