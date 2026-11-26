package com.evasi.downloader.media

import org.junit.Assert.*
import org.junit.Test

class PlatformVideoTest {
    private val link = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"

    @Test fun retainsPageForEngineAndOffersOnlyDownloadableVideoSizes() {
        val post = PlatformVideoRequest.parse("""{"title":"Video","formats":[
            {"vcodec":"none","ext":"m4a"},
            {"vcodec":"avc1","width":1920,"height":1080,"ext":"mp4"},
            {"vcodec":"vp9","width":1920,"height":1080},
            {"vcodec":"avc1","width":3840,"height":2160,"has_drm":true}
        ]}""", link)
        assertEquals(listOf(Quality(1920, 1080)), post.qualities)
        assertEquals(listOf(null, Quality(1920, 1080)), post.items.single().sources.map { it.quality })
        assertTrue(post.items.single().sources.all { it.platformVideo && it.uri == link })
        assertEquals("Video", post.title)
    }

    @Test fun explicitQualityConstrainsVisualTracksButCanMergeAudio() {
        val request = PlatformVideoRequest.forLink(link, Quality(640, 360))
        val filter = "[width=640][height=360]"
        assertEquals("bv*$filter[ext=mp4]+ba[ext=m4a]/b$filter[ext=mp4]/bv*$filter+ba/b$filter/bv$filter", request.getOption("-f"))
        assertEquals("1", request.getOption("--playlist-items"))
        assertTrue(request.hasOption("--no-playlist"))
    }

    @Test fun rejectsLiveAudioAndDrmOnlyResults() {
        for (data in listOf("""{"is_live":true}""", """{"formats":[{"vcodec":"none"}]}""",
            """{"formats":[{"vcodec":"avc1","has_drm":true}]}""")) {
            assertEquals(FailureReason.UNSUPPORTED, assertThrows(MediaFailure::class.java) {
                PlatformVideoRequest.parse(data, link)
            }.reason)
        }
    }
}
