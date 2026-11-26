package com.evasi.downloader.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SocialRedirectTest {
    @Test fun followsProviderCdnRedirectAndStopsBeforeSendingRefererOutsideItsDomains() {
        val first = "https://media.cdninstagram.com/first.jpg"
        val sibling = "https://scontent.xx.fbcdn.net/second.jpg"
        val post = InstagramAlbum.parse("""<script type="application/json">{"shortcode_media":{
            "code":"EXAMPLE","media_type":1,"image_versions2":{"candidates":[{"url":"$first"}]}}}</script>""",
            URI("https://www.instagram.com/p/EXAMPLE/"))
        val item = requireNotNull(post).items.single()
        val requested = mutableListOf<String>()
        val referers = mutableListOf<String?>()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = MediaRepository(context) { uri ->
            requested += uri.toString()
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() {}
                override fun usingProxy() = false
                override fun disconnect() {}
                override fun getResponseCode(): Int {
                    referers += getRequestProperty("Referer")
                    return HTTP_MOVED_TEMP
                }
                override fun getHeaderField(name: String) = if (name == "Location") {
                    if (uri.toString() == first) sibling else "https://unrelated.example/third.jpg"
                } else null
            }
        }
        val failure = assertThrows(MediaFailure::class.java) {
            runBlocking { repository.save(item, null) { _, _ -> } }
        }
        assertEquals(FailureReason.UNAVAILABLE, failure.reason)
        assertEquals(listOf(first, sibling), requested)
        assertEquals(listOf("https://www.instagram.com/", "https://www.instagram.com/"), referers)
    }
}
