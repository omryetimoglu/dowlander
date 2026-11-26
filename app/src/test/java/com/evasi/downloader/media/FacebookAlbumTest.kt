package com.evasi.downloader.media

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class FacebookAlbumTest {
    private val page = URI("https://www.facebook.com/example/posts/123/")
    private val first = "https://scontent.xx.fbcdn.net/photos/other-name.jpg?sig=abc"
    private val last = "https://scontent.xx.fbcdn.net/photos/last.jpg"

    // Facebook's Comet feed and photo viewer are separate Relay responses.
    private fun album(nodes: String, count: Int = 3, more: Boolean = false, id: String = "123") = """
        <script type="application/json" data-sjs>{"require":[["RelayPrefetchedStreamCache","next",[],["test",{
          "__bbox":{"result":{"data":{"node":{"post_id":"$id","comet_sections":{"content":{"story":{"attachments":[
            {"style_type_renderer":{"attachment":{"all_subattachments":{"nodes":[$nodes],"count":$count,
              "page_info":{"has_next_page":$more}}}}}
          ]}}}}}}}}]]]}</script>
    """.trimIndent()

    private val nodes = """
      {"media":{"id":"11","__typename":"Photo","image":{"uri":"https://scontent.xx.fbcdn.net/grid-thumb.jpg"}}},
      {"media":{"id":"22","__typename":"Video"}},
      {"media":{"id":"33","__typename":"Photo","photo_image":{"uri":"$last","width":1200,"height":800}}}
    """.trimIndent()

    private fun photo() = """
        <script type="application/json">{"__bbox":{"result":{"data":{"currMedia":{"id":"11",
        "__typename":"Photo","image":{"uri":"$first","width":1600,"height":1200}}}}}}</script>
    """

    @Test fun postResolvesEveryAttachmentInOrderAndFetchesViewerForGridThumbnail() {
        val requested = mutableListOf<URI>()
        val unrelated = album(nodes.replace("33", "999"), id = "999")
        val post = requireNotNull(FacebookAlbum.parse(unrelated + album(nodes), page) { requested += it; photo() })
        assertEquals(listOf(URI("https://www.facebook.com/photo/?fbid=11")), requested)
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.VIDEO, MediaKind.PHOTO), post.items.map { it.kind })
        val sources = post.items.map { it.sources.single() }
        assertEquals(listOf(first, "https://www.facebook.com/watch/?v=22", last), sources.map { it.uri })
        assertTrue(sources[1].platformVideo)
        assertEquals(Quality(1600, 1200), sources[0].quality)
    }

    @Test fun cannotSavePartialAlbumsOrUnrelatedPhotoFromViewer() {
        for (body in listOf(album(nodes, count = 4), album(nodes, more = true), album(nodes))) {
            val failure = assertThrows(MediaFailure::class.java) {
                FacebookAlbum.parse(body, page) { photo().replace("\"11\"", "\"77\"") }
            }
            assertEquals(FailureReason.UNAVAILABLE, failure.reason)
        }
    }

    @Test fun completeCappedGridAllowsAllCountOrAnExplicitEndButRejectsAPreview() {
        fun parse(body: String) = FacebookAlbum.parse(body, page) { photo() }
        val grid = album(nodes).replace("\"all_subattachments\":", "\"five_photos_subattachments\":")
        val withTotal = grid.replace("\"five_photos_subattachments\":",
            "\"all_subattachments\":{\"count\":3},\"five_photos_subattachments\":")
        assertEquals(3, parse(grid)?.items?.size)
        assertEquals(3, parse(withTotal.replace("\"page_info\"", "\"no_cursor\""))?.items?.size)
        for (partial in listOf(grid.replace("\"count\":3", "\"count\":8"),
            grid.replace("\"has_next_page\":false", "\"has_next_page\":true"),
            grid.replace("\"page_info\"", "\"no_cursor\""),
            withTotal.replaceFirst("\"count\":3", "\"count\":8"))) {
            assertThrows(MediaFailure::class.java) { parse(partial) }
        }
    }

    @Test fun videoOnlyFallsThroughAndDedicatedPhotoUsesItsOwnId() {
        assertNull(FacebookAlbum.parse(album("""{"media":{"id":"7","__typename":"Video"}}""", count = 1), page) { error("unexpected request") })
        val item = FacebookAlbum.parse(photo(), URI("https://www.facebook.com/photo/?fbid=11")) { error("unexpected request") }
        assertEquals(first, item?.items?.single()?.sources?.single()?.uri)
    }
}
