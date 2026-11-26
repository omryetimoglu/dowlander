package com.evasi.downloader.media

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore.MediaColumns
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.evasi.downloader.DownloadModel
import com.evasi.downloader.MainActivity
import com.evasi.downloader.Preferences
import com.evasi.downloader.ui.Phase
import com.evasi.downloader.ui.Source
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GalleryPublicationTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var media: FileMediaProvider
    private lateinit var repo: MediaRepository

    @Before fun registerProvider() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        media = FileMediaProvider(temp.root)
        media.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", media)
        repo = MediaRepository(app)
    }

    @Test fun sharedGifPublishesOnlyAfterWritingIdenticalBytes() = runBlocking {
        val gif = gifWithComment(1)
        media.source.writeBytes(gif)
        val item = repo.resolveShared(media.sourceUri, "image/gif").items.single()
        val output = repo.save(item, null) { _, _ -> }
        assertEquals(media.lastInserted, output)
        assertEquals("image/gif", media.values.getAsString(MediaColumns.MIME_TYPE))
        assertEquals("Pictures/Evasi Video Dowlander/", media.values.getAsString(MediaColumns.RELATIVE_PATH))
        assertTrue(output.path.orEmpty().contains("/images/media/"))
        assertEquals(listOf("pending", "write", "publish"), media.events)
        assertArrayEquals(gif, media.output.readBytes())
    }

    @Test fun truncatedInputDeletesItsPendingRow() = runBlocking {
        val gif = gifWithComment(1)
        media.source.writeBytes(gif)
        media.declaredSize = gif.size.toLong() + 100
        val item = repo.resolveShared(media.sourceUri, "image/gif").items.single()
        try {
            repo.save(item, null) { _, _ -> }
            fail("Expected the advertised length mismatch to fail")
        } catch (failure: MediaFailure) {
            assertEquals(FailureReason.INVALID, failure.reason)
        }
        assertEquals(listOf("pending", "write", "delete"), media.events)
        assertFalse(media.output.exists())
    }

    @Test fun cancellationAfterFirstChunkDeletesThePartialFile() = runBlocking {
        val gif = gifWithComment(700)
        media.source.writeBytes(gif)
        val item = repo.resolveShared(media.sourceUri, "image/gif").items.single()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val transfer = launch {
            val operation = coroutineContext.job
            repo.save(item, null) { bytes, _ ->
                if (bytes in 1 until gif.size.toLong()) operation.cancel()
            }
        }
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertTrue(media.deletedLength in 1 until gif.size.toLong())
        assertEquals(listOf("pending", "write", "delete"), media.events)
        assertFalse(media.output.exists())
    }

    /** Optional live sources previously hashed independently. The provider is file-backed on this JVM. */
    @Test @Config(sdk = [29])
    fun liveColdVideoAndWarmPhotoSharesWriteEachOriginalOnce() {
        val video = "https://mastodon.gamedev.place/@thomas_kole/116385659543124875"
        val photo = "https://mastodon.gamedev.place/@ephtracy/113995266669577850"
        val posts = System.getenv("EVASI_PUBLIC_POSTS").orEmpty().split(' ').filter(String::isNotBlank)
        assumeTrue("Set EVASI_PUBLIC_POSTS to both reference posts", posts.containsAll(listOf(video, photo)))
        val app = ApplicationProvider.getApplicationContext<Application>()
        Preferences(app).autoStart = false
        val clipboard = requireNotNull(app.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("old", "https://mastodon.social/@example/99"))
        fun share(url: String) = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Look at this! $url #media")
        val controller = Robolectric.buildActivity(MainActivity::class.java, share(video)).create().start().resume()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[DownloadModel::class.java]
            assertSame(app, model.getApplication<Application>())
            fun verify(url: String, collection: String, expectedHash: String) {
                activity.onWindowFocusChanged(true)
                val initial = model.session.state.value
                assertEquals(Source.Share, initial.source)
                assertEquals(url, initial.link)
                assertNull(initial.countdown)
                val main = shadowOf(Looper.getMainLooper())
                // Run actual main-dispatcher continuations while network and file IO run on Dispatchers.IO.
                for (attempt in 1..900) {
                    main.idle()
                    if (model.session.state.value.phase in setOf(Phase.Saved, Phase.Failed)) break
                    Thread.sleep(100)
                }
                val result = model.session.state.value
                assertEquals("Download result for $url: ${result.error}", Phase.Saved, result.phase)
                assertEquals(1, result.savedCount)
                assertEquals(media.lastInserted.toString(), model.session.galleryUri)
                assertTrue(media.lastInserted.path.orEmpty().contains(collection))
                assertEquals(listOf("pending", "write", "publish"), media.events)
                val digest = MessageDigest.getInstance("SHA-256").digest(media.output.readBytes())
                assertEquals(expectedHash, digest.joinToString("") { "%02x".format(it) })
            }
            verify(video, "/video/media/", "082ea55ba68fcb517180cced566126bbf7b0b5db6c67fdc993707e6ab68f79e1")
            media.events.clear()
            controller.newIntent(share(photo))
            val generation = model.session.state.value.controlsGeneration
            controller.newIntent(share(photo))
            assertEquals(generation, model.session.state.value.controlsGeneration)
            verify(photo, "/images/media/", "3311bffaa230023f53e89402bd9bc45b25b67fa89f2ce2ff5335215f96aae2cd")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun gifWithComment(blocks: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun hex(s: String) = s.chunked(2).forEach { out.write(it.toInt(16)) }
        hex("47494638396101000100800000000000ffffff21fe")
        repeat(blocks) { out.write(255); out.write(ByteArray(255) { 65 }) }
        hex("002c00000000010001000002024401003b")
        return out.toByteArray()
    }

    private class FileMediaProvider(val folder: File) : ContentProvider() {
        val source = File(folder, "original.gif")
        val sourceUri: Uri = Uri.parse("content://media/test/source")
        lateinit var output: File
        lateinit var lastInserted: Uri
        lateinit var values: ContentValues
        var declaredSize: Long? = null
        var deletedLength = 0L
        val events = mutableListOf<String>()
        private var nextId = 0L
        override fun onCreate() = true
        override fun getType(uri: Uri) = if (uri == sourceUri) "image/gif" else values.getAsString(MediaColumns.MIME_TYPE)

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> when (column) {
                    OpenableColumns.DISPLAY_NAME -> source.name
                    OpenableColumns.SIZE -> declaredSize ?: source.length()
                    MediaColumns.IS_PENDING -> values.getAsInteger(MediaColumns.IS_PENDING)
                    else -> null
                } })
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = if (uri == sourceUri) source else output.also {
                if (mode.contains('w')) {
                    check(values.getAsInteger(MediaColumns.IS_PENDING) == 1)
                    events.add("write")
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            this.values = ContentValues(requireNotNull(values))
            check(this.values.getAsInteger(MediaColumns.IS_PENDING) == 1)
            events.add("pending")
            lastInserted = ContentUris.withAppendedId(uri, ++nextId)
            output = File(folder, "row-$nextId")
            check(output.createNewFile())
            return lastInserted
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == lastInserted && values?.getAsInteger(MediaColumns.IS_PENDING) == 0 && output.length() > 0)
            this.values.putAll(values)
            events.add("publish")
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == lastInserted)
            deletedLength = output.length()
            check(output.delete())
            events.add("delete")
            return 1
        }
    }
}
