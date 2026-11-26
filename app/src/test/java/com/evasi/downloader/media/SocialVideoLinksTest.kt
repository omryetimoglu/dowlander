package com.evasi.downloader.media

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialVideoLinksTest {
    private fun normalized(link: String) = SocialVideoLinks.match(URI(link))?.toString()

    @Test fun normalizesSingleYoutubeVideosAndRemovesPlaylistAndTracking() {
        val id = "aA1_-bB2cC3"
        val watch = "https://www.youtube.com/watch?v=$id"
        for (url in listOf(
            "https://youtu.be/$id?si=abc",
            "http://m.youtube.com:80/watch?list=playlist&v=$id&t=3s",
            "https://www.youtube.com/shorts/$id#comments",
            "https://youtube.com/live/$id/",
        )) assertEquals(url, watch, normalized(url))
    }

    @Test fun preservesShortLinkCodesAndNormalizesPublicPostPaths() {
        val samples = mapOf(
            "https://www.instagram.com/reel/DgLl94koIXQ/?utm_source=ig_web_copy_link&lqvp=NTc4MTIwNjQ2YQ==" to
                "https://www.instagram.com/reel/DgLl94koIXQ/",
            "https://www.youtube.com/shorts/50MR8HO7cy0" to "https://www.youtube.com/watch?v=50MR8HO7cy0",
            "https://www.tiktok.com/@burak_dag06/video/7705780212738575636?is_from_webapp=1&sender_device=pc" to
                "https://www.tiktok.com/@burak_dag06/video/7705780212738575636",
            "https://m.facebook.com/watch/?ref=sharing&v=12345" to "https://www.facebook.com/watch/?v=12345",
            "https://www.facebook.com/share/r/Ab2/?mibextid=1" to "https://www.facebook.com/share/r/Ab2/",
            "https://fb.watch/xTh_8Zo/" to "https://fb.watch/xTh_8Zo/",
            "https://facebook.com/reel/456" to "https://www.facebook.com/reel/456/",
            "https://m.facebook.com/example/videos/456/" to "https://www.facebook.com/example/videos/456/",
            "https://instagram.com/example/reel/Ab_2-/?igsh=abc" to "https://www.instagram.com/reel/Ab_2-/",
            "https://instagram.com/share/reel/ABc9/" to "https://www.instagram.com/share/reel/ABc9/",
            "https://instagram.com/p/P_1/" to "https://www.instagram.com/p/P_1/",
            "https://vm.tiktok.com/AbC123/?_t=abc" to "https://vm.tiktok.com/AbC123/",
            "https://www.tiktok.com/t/ZTSa12/" to "https://www.tiktok.com/t/ZTSa12/",
            "https://m.tiktok.com/@an.example/video/98765?lang=en" to "https://www.tiktok.com/@an.example/video/98765",
        )
        samples.forEach { (url, expected) -> assertEquals(url, expected, normalized(url)) }
    }

    @Test fun rejectsProfilesPlaylistsRedirectsAndLookalikeHosts() {
        for (url in listOf(
            "https://youtube.com/playlist?list=abc",
            "https://youtube.com/watch?v=%2F..%2Fsecret",
            "https://instagram.com/username/",
            "https://vt.tiktok.com/a/b/",
            "https://www.facebook.com/l.php?u=https://example.com",
            "https://youtube.com.evil.test/shorts/aA1_-bB2cC3",
            "ftp://vm.tiktok.com/abc/",
            "https://username@instagram.com/reel/x/",
            "https://vm.tiktok.com:8443/abc/",
        )) assertNull(url, normalized(url))
    }
}
