package com.evasi.downloader

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ShareGalleryTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test
    fun coldShareThenNewShareAndRotationPublishEachAnimatedFileExactlyOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val first = gif("A-$id")
        val second = gif("B-$id")
        val sources = mutableSetOf<Uri>()
        val label = Preferences.localized(context).getString(R.string.saved_title)
        val store = context.getSharedPreferences("evasi", Context.MODE_PRIVATE)
        val previousSharedAt = store.all["share_time"] as? Long

        try {
            val a = insertFixture(resolver, first, "A-$id.gif").also(sources::add)
            val b = insertFixture(resolver, second, "B-$id.gif").also(sources::add)
            ActivityScenario.launch<MainActivity>(share(context, a)).use { scenario ->
                ui.waitUntil(15_000) { matchingPublished(resolver, first).isNotEmpty() }
                ui.waitUntil(5_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }

                lateinit var original: MainActivity
                scenario.onActivity {
                    original = it
                    it.startActivity(share(it, b))
                }
                ui.waitUntil(5_000) {
                    var received = false
                    scenario.onActivity {
                        @Suppress("DEPRECATION")
                        received = it.intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) == b
                    }
                    received
                }
                var orientation = Configuration.ORIENTATION_UNDEFINED
                scenario.onActivity {
                    assertSame(original, it)
                    orientation = if (it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                        Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    it.requestedOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                ui.waitUntil(10_000) {
                    var rotated = false
                    scenario.onActivity { rotated = it.resources.configuration.orientation == orientation && it !== original }
                    rotated
                }
                ui.waitUntil(15_000) { matchingPublished(resolver, second).isNotEmpty() }
                ui.waitUntil(5_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                for (bytes in listOf(first, second)) {
                    val output = matchingPublished(resolver, bytes)
                    assertEquals(1, output.size)
                    assertArrayEquals(bytes, resolver.openInputStream(output.single())!!.use { it.readBytes() })
                }
            }
        } finally {
            // Each generated GIF includes this test's random identifier. Only those exact bytes match.
            for (uri in (matchingPublished(resolver, first) + matchingPublished(resolver, second) + sources).distinct()) {
                resolver.delete(uri, null, null)
            }
            val editor = store.edit()
            if (previousSharedAt == null) editor.remove("share_time") else editor.putLong("share_time", previousSharedAt)
            editor.commit()
        }
    }

    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun share(context: Context, source: Uri) = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "image/gif"
        putExtra(Intent.EXTRA_STREAM, source)
        clipData = ClipData.newRawUri(null, source)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun insertFixture(resolver: ContentResolver, bytes: ByteArray, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/EvasiInstrumentation")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(images, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun matchingPublished(resolver: ContentResolver, bytes: ByteArray): List<Uri> = buildList {
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=0 AND ${MediaStore.MediaColumns.MIME_TYPE}=? " +
            "AND ${MediaStore.MediaColumns.SIZE}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        resolver.query(images, arrayOf(MediaStore.MediaColumns._ID), selection,
            arrayOf("image/gif", bytes.size.toString(), "Pictures/eVasi Dowlander App/"), null)?.use { rows ->
            while (rows.moveToNext()) {
                val uri = ContentUris.withAppendedId(images, rows.getLong(0))
                val actual = resolver.openInputStream(uri)?.use { it.readBytes() }
                if (actual != null && bytes.contentEquals(actual)) add(uri)
            }
        }
    }

    /** Two single-pixel black/white frames, a looping extension and a unique comment. */
    private fun gif(marker: String): ByteArray {
        val result = ByteArrayOutputStream()
        fun hex(value: String) = value.split(' ').forEach { result.write(it.toInt(16)) }
        result.write("GIF89a".toByteArray(Charsets.US_ASCII))
        hex("01 00 01 00 80 00 00 00 00 00 FF FF FF")
        hex("21 FF 0B")
        result.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        hex("03 01 00 00 00 21 FE")
        result.write(marker.length)
        result.write(marker.toByteArray(Charsets.US_ASCII))
        result.write(0)
        for (pixel in listOf("44", "4C")) {
            hex("21 F9 04 00 0A 00 00 00 2C 00 00 00 00 01 00 01 00 00 02 02")
            result.write(pixel.toInt(16))
            hex("01 00")
        }
        result.write(0x3B)
        return result.toByteArray()
    }
}
