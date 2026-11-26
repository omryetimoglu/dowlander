package com.evasi.downloader.media

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ThreadsAlbumTest {
    private val page = URI("https://www.threads.com/@someone/post/AbC-12")
    private val photo = """{"media_type":1,"image_versions2":{"candidates":[
        {"url":"https://scontent.xx.fbcdn.net/cover.jpg?w=100","width":100,"height":100},
        {"url":"https://scontent.xx.fbcdn.net/full.jpg","width":800,"height":700}]}}"""
    private val video = """{"media_type":2,"video_versions":[{"url":"https://cdninstagram.com/video.mp4","width":480,"height":640}]}"""

    private fun page(post: String) = """<script data-sjs type="application/json">{"require":[["RelayPrefetchedStreamCache","next",[],
        [{"__bbox":{"result":{"data":{"data":{"containing_thread":{"thread_items":[
        {"post":{"code":"another","media_type":1}},{"post":$post}]}}}}}}]]]}</script>"""

    @Test fun normalizesSupportedHostsAndRejectsUnrelatedHostsAndProfilePages() {
        assertEquals(page, ThreadsAlbum.endpoint(URI("https://threads.net/@someone/post/AbC-12/?xmt=tracking")))
        assertEquals("https://www.threads.com/t/code", ThreadsAlbum.endpoint(URI("https://www.threads.com/t/code/"))?.toString())
        assertNull(ThreadsAlbum.endpoint(URI("https://threads.com/@someone")))
        assertNull(ThreadsAlbum.endpoint(URI("https://threads.net.bad.example/@someone/post/AbC-12")))
    }

    @Test fun selectedPostsMediaRemainOrderedAndUseTheirOwnSources() {
        val post = """{"code":"AbC-12","user":{"username":"someone"},"media_type":8,
            "carousel_media_count":3,"carousel_media":[$photo,$video,$photo]}"""
        val result = requireNotNull(ThreadsAlbum.parse(page(post), page))
        assertEquals("someone", result.title)
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.VIDEO, MediaKind.PHOTO), result.items.map { it.kind })
        assertEquals(listOf("https://scontent.xx.fbcdn.net/full.jpg", "https://cdninstagram.com/video.mp4",
            "https://scontent.xx.fbcdn.net/full.jpg"), result.items.map { it.sources.first().uri })
        assertEquals("https://www.threads.com/", result.items.first().sources.first().referer)
    }

    @Test fun singleImageAndVideoWorkWithoutUsingSuggestedPosts() {
        for ((media, kind) in listOf(photo to MediaKind.PHOTO, video to MediaKind.VIDEO)) {
            val post = media.replaceFirst("{", """{"code":"AbC-12",""")
            assertEquals(kind, ThreadsAlbum.parse(page(post), page)?.items?.single()?.kind)
            assertNull(ThreadsAlbum.parse(page(post), URI("https://threads.net/t/missing")))
        }
    }

    @Test fun privateOrIncompletePostIsUnavailable() {
        for (post in listOf("""{"code":"AbC-12","user":{"is_private":true}}""",
            """{"code":"AbC-12","media_type":8,"carousel_media_count":2,"carousel_media":[$photo]}""")) {
            assertEquals(FailureReason.UNAVAILABLE, assertThrows(MediaFailure::class.java) {
                ThreadsAlbum.parse(page(post), page)
            }.reason)
        }
    }
}
