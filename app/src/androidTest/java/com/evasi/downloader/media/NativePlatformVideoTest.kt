package com.evasi.downloader.media

import android.content.res.AssetManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlatformVideoTest {
    @Test fun nativeExtractorAndFfmpegPublishSeparateVideoAndAudioAsOneMovie() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = MediaRepository(context)
        DashFixtureServer(instrumentation.context.assets).use { server ->
            val quality = Quality(128, 96)
            val post = withTimeout(300_000) { PlatformVideo(context).resolve(server.url) }
            assertEquals(listOf(quality), post.qualities)
            val uri = withTimeout(300_000) { repository.save(post.items.single(), quality) { _, _ -> } }
            try {
                val resolver = context.contentResolver
                requireNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING),
                    null, null, null)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                val extractor = MediaExtractor()
                try {
                    requireNotNull(resolver.openFileDescriptor(uri, "r")).use { fd ->
                        extractor.setDataSource(fd.fileDescriptor)
                    }
                    val tracks = (0 until extractor.trackCount).map { i ->
                        extractor.selectTrack(i)
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        assertTrue("Track $i has no media samples", extractor.sampleSize > 0)
                        extractor.unselectTrack(i)
                        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    }
                    assertEquals(listOf("audio/mp4a-latm", "video/avc"), tracks.filterNotNull().sorted())
                } finally {
                    extractor.release()
                }
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    /** The two one-second streams contain a blue H.264 frame sequence and a 440 Hz AAC tone. */
    private class DashFixtureServer(assets: AssetManager) : Closeable {
        private val files = mapOf("clip.mpd" to "application/dash+xml", "video.mp4" to "video/mp4",
            "audio.m4a" to "audio/mp4").mapValues { (name, mime) ->
            mime to assets.open("dash/$name").use { it.readBytes() }
        }
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/clip.mpd"
        private val worker = thread(name = "dash-test-server", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (error: SocketException) {
                    if (server.isClosed) break else throw error
                }
                socket.use {
                    // Extractors can close a probe request after reading the headers.
                    try { respond(it) } catch (_: IOException) { }
                }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val request = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val line = request.readLine()?.split(' ') ?: return
            while (!request.readLine().isNullOrEmpty()) { }
            val file = files[line.getOrNull(1)?.removePrefix("/")]
            val out = socket.getOutputStream()
            if (file == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val (mime, bytes) = file
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            if (line.first() == "GET") out.write(bytes)
        }

        override fun close() {
            server.close()
            worker.join(6_000)
        }
    }
}
