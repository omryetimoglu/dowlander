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
        val request = PlatformVideoRequest.download(MediaSource(link, quality = Quality(640, 360)))
        val filter = "[width=640][height=360]"
        assertEquals("bv*$filter[ext=mp4]+ba[ext=m4a]/b$filter[ext=mp4]/bv*$filter+ba/b$filter/bv$filter", request.getOption("-f"))
        assertEquals("1", request.getOption("--playlist-items"))
        assertTrue(request.hasOption("--no-playlist"))
    }

    @Test fun mixedAlbumDownloadsEachSourceIndexAndNeverConvertsPostersToPhotos() {
        val album = "https://www.instagram.com/p/Album/"
        val metadata = PlatformVideoRequest.metadata(album)
        assertTrue(metadata.hasOption("--dump-single-json"))
        assertFalse(metadata.hasOption("--playlist-items"))
        val youtube = PlatformVideoRequest.metadata(link)
        assertTrue(youtube.hasOption("--no-playlist"))
        assertEquals("1", youtube.getOption("--playlist-items"))

        val post = PlatformVideoRequest.parse("""{"_type":"playlist","title":"Mixed post","entries":[
            {"title":"clip","formats":[{"vcodec":"h264","ext":"mp4"}],"thumbnail":"https://example.org/cover.jpg"},
            {"title":"still","playlist_index":3,"formats":[
              {"format_id":"photo2","ext":"jpg","url":"https://example.org/original.jpg","width":800,"height":600,"vcodec":"none","acodec":"none"},
              {"format_id":"photo1","ext":"jpg","url":"https://example.org/small.jpg","width":320,"height":240}]},
            {"title":"clip","playlist_index":5,"formats":[{"vcodec":"h264","ext":"mp4"}]}
        ]}""", album)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.PHOTO, MediaKind.VIDEO), post.items.map { it.kind })
        val sources = post.items.map { it.sources.first() }
        assertEquals(listOf(1, 3, 5), sources.map { it.playlistIndex })
        assertEquals(listOf("1","3","5"), sources.map { PlatformVideoRequest.download(it).getOption("--playlist-items") })
        assertEquals("photo2", PlatformVideoRequest.download(sources[1]).getOption("-f"))
        assertEquals("image/jpeg", sources[1].mime)
        assertEquals(Quality(800, 600), sources[1].quality)
    }

    @Test fun unavailableAlbumEntryCannotMasqueradeAsACompleteDownload() {
        val partial = """{"entries":[{"formats":[{"ext":"mp4","vcodec":"avc1"}]},
            {"title":"blocked","thumbnail":"https://example.org/not-an-attachment.jpg"}]}"""
        assertEquals(FailureReason.UNAVAILABLE, assertThrows(MediaFailure::class.java) {
            PlatformVideoRequest.parse(partial, "https://www.instagram.com/p/Album/")
        }.reason)
    }

    @Test fun extractsSingleNativePhotoWithNoFormatsArray() {
        val entry = """{"title":"A still","url":"https://example.org/media.jpg","ext":"jpg","format_id":"0","vcodec":"none","acodec":"none"}"""
        val item = PlatformVideoRequest.parse(entry, "https://www.instagram.com/p/abc/").items.single()
        assertEquals(MediaKind.PHOTO, item.kind)
        assertEquals("0", PlatformVideoRequest.download(item.sources.single()).getOption("-f"))
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
