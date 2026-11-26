package com.evasi.downloader.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class GalleryShareDeviceTest {
    @Test fun guestSharesRealPublishedGalleryFilesAndEveryReadGrantReachesSystemChooser() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val files = mutableListOf<Uri>()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            repeat(2) {
                val row = requireNotNull(resolver.insert(images, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "evasi-share-test-" + UUID.randomUUID() + ".png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/EvasiInstrumentation/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                files += row
                requireNotNull(resolver.openOutputStream(row)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertEquals(1, resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            }
            val intent = mediaShareChooser(context, files.map(Uri::toString))
            assertEquals(Intent.ACTION_CHOOSER, intent.action)
            val send = requireNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
            assertEquals(files, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
            assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
            for (granted in listOf(send, intent)) {
                val clip = requireNotNull(granted.clipData)
                assertEquals(files, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
                assertTrue(granted.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(0, granted.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } finally {
            bitmap.recycle()
            files.forEach { resolver.delete(it, null, null) }
        }
    }
}
