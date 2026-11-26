package com.evasi.downloader.share

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@Suppress("DEPRECATION")
class AndroidSharesTest {
    private val image = Uri.parse("content://media/external/images/media/7")
    private val video = Uri.parse("content://media/external/video/media/8")

    @Test fun singleVideoSharesFileWithoutTextOrWritePermission() {
        val send = mediaSendIntent(listOf(video to "video/mp4"), null)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/mp4", send.type)
        assertEquals(video, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        assertEquals(video, send.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
    }

    @Test fun mixedAlbumRetainsOrderGrantsAndEditedCaption() {
        val files = listOf(video to "video/mp4", image to "image/jpeg")
        val send = mediaSendIntent(files, "Edited caption")
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("*/*", send.type)
        assertEquals(files.map { it.first }, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("Edited caption", send.getStringExtra(Intent.EXTRA_TEXT))
        val clip = requireNotNull(send.clipData)
        assertEquals(listOf(video, image), (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)

        val photos = listOf(image to "image/jpeg", Uri.parse("content://media/external/images/media/9") to "image/png")
        assertEquals("image/*", mediaSendIntent(photos, null).type)
    }

    @Test fun arbitraryContentAuthoritiesAndFilePathsCannotBeSharedByHistory() {
        for (value in listOf("content://unknown.example/private/1", "file:///data/user/0/a.txt")) {
            assertThrows(IOException::class.java) {
                runBlocking { mediaShareChooser(ApplicationProvider.getApplicationContext(), listOf(value)) }
            }
        }
    }
}
