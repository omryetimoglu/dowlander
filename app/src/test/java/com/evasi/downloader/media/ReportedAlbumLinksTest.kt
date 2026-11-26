package com.evasi.downloader.media

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportedAlbumLinksTest {
    @Test fun instagramIndexLinkResolvesTheWholePost() {
        val link = URI("https://www.instagram.com/p/Df5NPowjsAA/?img_index=1")
        val page = requireNotNull(InstagramAlbum.endpoint(link))
        assertEquals(URI("https://www.instagram.com/p/Df5NPowjsAA/"), page)

        // Synthetic payload. Only the input link and shortcode come from the reported post.
        val html = """<script type="application/json" data-sjs>{"data":{"xig_polaris_media":{
            "if_not_gated_logged_out":{"code":"Df5NPowjsAA","media_type":8,"carousel_media":[
            {"media_type":1,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/fixture1.jpg"}]}},
            {"media_type":1,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/fixture2.jpg"}]}}]}}}}</script>"""
        val album = requireNotNull(InstagramAlbum.parse(html, page))
        assertEquals(listOf("fixture1.jpg", "fixture2.jpg"), album.items.map { URI(it.sources.first().uri).path.removePrefix("/") })
    }

    @Test fun xStatusLinkUsesOnlyStatusIdAndRequestsOriginalPhotos() {
        val link = "https://x.com/Yasin_Doqruer/status/2125701684244419061?a=nL_cGSccHWAvquA5aQg&s=20"
        val request = PostRequest.from(link)
        assertEquals(URI("https://cdn.syndication.twimg.com/tweet-result?id=2125701684244419061&lang=en&token=55i372tetd3"),
            request.endpoint)

        // Synthetic payload using the syndication API's ordered mediaDetails array.
        val album = request.parse("""{"id_str":"2125701684244419061","mediaDetails":[
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/fixture1.jpg"},
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/fixture2.png"}]}""")
        assertEquals(listOf("fixture1?format=jpg&name=orig", "fixture2?format=png&name=orig"),
            album.items.map { it.sources.first().uri.substringAfter("/media/") })
    }
}
