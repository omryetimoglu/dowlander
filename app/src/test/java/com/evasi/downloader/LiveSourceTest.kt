package com.evasi.downloader

import com.evasi.downloader.media.Mastodon
import com.evasi.downloader.media.MediaFormat
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional network check. Device publication and original byte identity are covered by PublicPostTest. */
class LiveSourceTest {
    @Test fun publicPostsResolveToReadableOriginalFiles() {
        val links = System.getenv("EVASI_PUBLIC_POSTS").orEmpty().split(' ').filter(String::isNotBlank)
        assumeTrue("Set EVASI_PUBLIC_POSTS to run the live-source check", links.isNotEmpty())
        for (link in links) {
            val endpoint = Mastodon.endpoint(link)
            val post = request(endpoint.toURL()) { stream, _ -> Mastodon.parse(stream.bufferedReader().readText()) }
            assertTrue("No attachment in $link", post.items.isNotEmpty())
            for (item in post.items) {
                val source = item.sources.first()
                request(URL(source.uri)) { stream, length ->
                    val buffered = stream.buffered()
                    val format = MediaFormat.inspect(buffered, source.mime)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = buffered.read(buffer)
                        if (count < 0) break
                        bytes += count
                        digest.update(buffer, 0, count)
                    }
                    assertTrue(bytes > 0)
                    if (length >= 0) assertEquals(length, bytes)
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    println("$link  ${format.mime}  ${source.quality}  $bytes bytes  SHA-256 $hash")
                }
            }
        }
    }

    private fun <T> request(url: URL, block: (InputStream, Long) -> T): T {
        val connection = url.openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "EvasiVideoDowlander/1.0 (verification)")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return connection.getInputStream().use { block(it, connection.contentLengthLong) }
    }
}
