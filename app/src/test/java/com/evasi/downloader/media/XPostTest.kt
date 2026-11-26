package com.evasi.downloader.media

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class XPostTest {
    private val sample = "https://x.com/militerenstitu/status/2125825261702635789?a=sPEOnbXrlYwpbvGsINM&s=20"
    private val high = "https://video.twimg.com/ext_tw_video/12/pu/vid/avc1/1280x720/high.mp4?tag=12"
    private val low = "https://video.twimg.com/ext_tw_video/12/pu/vid/avc1/640x360/low.mp4"

    @Test fun resolvesCopiedPostToProgressiveVideoInQualityOrder() {
        val request = PostRequest.from(sample)
        assertEquals("cdn.syndication.twimg.com", request.endpoint.host)
        assertEquals("id=2125825261702635789&lang=en&token=55ih682sflo", request.endpoint.query)
        val post = request.parse("""{
          "id_str":"2125825261702635789","user":{"name":"Example"},"mediaDetails":[
            {"type":"video","video_info":{"variants":[
              {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/stream.m3u8"},
              {"content_type":"video/mp4","bitrate":256000,"url":"$low"},
              {"content_type":"video/mp4","bitrate":2048000,"url":"$high"}
            ]}}]}""")
        assertEquals("Example", post.title)
        assertEquals(MediaKind.VIDEO, post.items.single().kind)
        assertEquals(listOf(high, low), post.items.single().sources.map { it.uri })
        assertEquals(listOf(Quality(1280, 720), Quality(640, 360)), post.qualities)
    }

    @Test fun embedWithOnlyTopLevelVideoKeepsPlayableSource() {
        val post = XPost.parse("""{"id_str":"42","video":{"variants":[
            {"type":"video/mp4","src":"$low"},{"type":"video/mp4","src":"$high"}]}}""")
        assertEquals(high, post.items.single().sources.first().uri)
    }

    @Test fun unavailablePostAndMissingDownloadableVideoHaveDistinctOutcomes() {
        assertEquals(FailureReason.UNAVAILABLE, assertThrows(MediaFailure::class.java) {
            XPost.parse("""{"__typename":"TweetTombstone"}""")
        }.reason)
        assertEquals(FailureReason.UNSUPPORTED, assertThrows(MediaFailure::class.java) {
            XPost.parse("""{"id_str":"42","video":{"variants":[
              {"type":"video/mp4","src":"https://video.twimg.com.evil.example/a.mp4"},
              {"type":"application/x-mpegURL","src":"https://video.twimg.com/a.m3u8"}]}}""")
        }.reason)
        assertTrue(XPost.parse("""{"id_str":"42","text":"text only"}""").items.isEmpty())
    }

    @Test fun dispatchRecognizesTwitterAliasesAndPreservesMastodon() {
        assertEquals("123", XPost.statusId(URI("https://mobile.twitter.com/i/web/status/123")))
        assertNull(XPost.statusId(URI("https://x.com.example.org/name/status/123")))
        assertNull(XPost.statusId(URI("https://x.com@other.example/name/status/123")))
        assertEquals("https://mastodon.social/api/v1/statuses/123",
            PostRequest.from("https://mastodon.social/@someone/123").endpoint.toString())
    }
}
