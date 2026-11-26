package com.evasi.downloader.queue

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.evasi.downloader.share.mediaShareChooser
import com.evasi.downloader.sharedInput
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueuedGalleryShareTest {
    @Suppress("DEPRECATION")
    @Test fun sharedPhotoFlowsThroughWorkerRoomMediaStoreAndFileOnlySharesheet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = DownloadQueue.get(context)
        val bitmap = Bitmap.createBitmap(17, 11, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.GREEN)
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val source = requireNotNull(resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "fixture-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var jobId: String? = null
        var sourceDeleted = false
        try {
            requireNotNull(resolver.openOutputStream(source)).use { it.write(bytes) }
            assertEquals(1, resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, source).apply {
                clipData = ClipData.newRawUri("photo", source)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val id = queue.enqueue(sharedInput(send), force = true)
            jobId = id
            // A transient sender no longer needs to be available once the queue owns a staged copy.
            assertEquals(1, resolver.delete(source, null, null))
            sourceDeleted = true
            val terminal = withTimeout(90_000) {
                queue.jobs.first { rows -> rows.any { it.id == id && it.state in setOf(QueueState.COMPLETED, QueueState.FAILED) } }
                    .single { it.id == id }
            }
            assertEquals("Worker failed: ${terminal.error}", QueueState.COMPLETED, terminal.state)
            assertEquals(1, terminal.savedCount)
            val record = withTimeout(10_000) { queue.history.first { rows -> rows.any { it.jobId == id } }.single { it.jobId == id } }
            assertEquals("Android", record.platform)
            assertEquals("image/png", record.mime)
            assertEquals(bytes.size.toLong(), record.size)
            assertEquals(17, record.width)
            assertEquals(11, record.height)
            assertFalse(record.missing)
            val uri = Uri.parse(record.uri)
            assertEquals("media", uri.authority)
            assertNotEquals(source, uri)
            assertArrayEquals(bytes, requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() })

            val chooser = mediaShareChooser(context, listOf(record.uri))
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val outgoing = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, outgoing.action)
            assertEquals("image/png", outgoing.type)
            assertEquals(uri, outgoing.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals(uri, outgoing.clipData?.getItemAt(0)?.uri)
            assertTrue(outgoing.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(outgoing.hasExtra(Intent.EXTRA_TEXT))

            assertEquals(1, context.contentResolver.delete(uri, null, null))
            queue.refreshHistory()
            val missing = withTimeout(10_000) {
                queue.history.first { rows -> rows.any { it.jobId == id && it.missing } }.single { it.jobId == id }
            }
            assertEquals(record.uri, missing.uri)
        } finally {
            if (!sourceDeleted) resolver.delete(source, null, null)
            jobId?.let { id ->
                queue.cancel(id)
                for (uri in queue.outputUris(id)) queue.deleteHistory(uri)
            }
        }
    }
}
