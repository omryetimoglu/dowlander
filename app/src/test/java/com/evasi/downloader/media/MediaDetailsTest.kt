package com.evasi.downloader.media

import org.junit.Assert.*
import org.junit.Test

class MediaDetailsTest {
    @Test fun syntheticSocialTitleCannotExposeOriginalCaptionInGuestFilename() {
        val post = PlatformVideoRequest.parse("""{"title":"A post with private...","description":"A post with private text.","uploader":"User","formats":[{"vcodec":"h264","ext":"mp4"}]}""",
            "https://www.tiktok.com/@user/video/123")
        assertEquals("A post with private text.", post.description)
        assertEquals("User", post.title)
        assertEquals("User.mp4", post.items.single().sources.single().name)
    }

    @Test fun nativeSplitTracksIncludeAudioInSizeAndDoNotRequireSecondCaptionRequest() {
        val post = PlatformVideoRequest.parse("""{"title":"Nature","description":"Original caption","thumbnail":"https://i.ytimg.com/vi/id/1.jpg","duration":10,"formats":[
            {"vcodec":"none","acodec":"aac","filesize":1000,"ext":"m4a"},
            {"vcodec":"h264","acodec":"aac","width":640,"height":360,"filesize":3500,"ext":"mp4"},
            {"vcodec":"av1","acodec":"none","width":3840,"height":2160,"filesize":17000,"ext":"webm"}
        ]}""", "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        assertEquals("Original caption", post.description)
        assertEquals("https://i.ytimg.com/vi/id/1.jpg", post.previewUrl)
        assertEquals(18000L, post.estimatedBytes(null))
        assertEquals(18000L, post.estimatedBytes(Quality(3840, 2160)))
        assertEquals(3500L, post.estimatedBytes(Quality(640, 360)))
        assertNull(post.estimatedBytes(Quality(1280, 720)))
    }

    @Test fun speedEtaUnknownLengthAndAudioVideoProgressRespectActualTransfer() {
        val events = mutableListOf<Pair<Long, Long?>>()
        val streams = NativeTransferProgress(null, 2) { bytes, total -> events.add(bytes to total) }
        streams.update("bytes 137 100 500")
        streams.update("bytes 137 500 500")
        streams.update("bytes 140 50 150")
        streams.update("bytes 140 10 150")
        streams.complete(620)
        assertEquals(listOf(100L to null, 500L to null, 550L to 650L, 550L to 650L, 550L to 550L), events)
        val meter = TransferMeter()
        assertNull(meter.sample(0, null, 1000).speedBytesPerSecond)
        assertEquals(TransferStats(1000, 2), meter.sample(1000, 3000, 2000))
        assertEquals(TransferStats(1000, null), meter.sample(2000, null, 3000))
        assertEquals(TransferStats(null, null), meter.sample(0, 3000, 4000))
    }

    @Test fun rejectsPathsOutsideGalleryCollectionsAndAllowsNamedSubfolders() {
        assertEquals("Work/Clips", normalizeAlbum("Work/Clips"))
        for (value in listOf("../escape", "/root", "Work/../Movies", ".hidden", "a\\b", "a///b", "a\nfile"))
            assertThrows(MediaFailure::class.java) { normalizeAlbum(value) }
    }
}
