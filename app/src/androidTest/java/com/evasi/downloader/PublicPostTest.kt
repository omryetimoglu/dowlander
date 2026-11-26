package com.evasi.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.evasi.downloader.ui.Phase
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Supply -Pandroid.testInstrumentationRunnerArguments.postUrl=<public single-attachment Mastodon URL>. */
class PublicPostTest {
    @Test fun shareSavesOriginalBytesWithoutASecondTap() {
        val url = InstrumentationRegistry.getArguments().getString("postUrl").orEmpty()
        assumeTrue("A live post is supplied for this test run", url.isNotBlank())
        val post = Uri.parse(url)
        val id = requireNotNull(post.lastPathSegment)
        require(id.all(Char::isDigit) && post.scheme == "https")
        val endpoint = URL("https://" + post.encodedAuthority + "/api/v1/statuses/" + id)
        val json = read(endpoint) { JSONObject(it.bufferedReader().readText()) }
        val attachments = (json.optJSONObject("reblog") ?: json).getJSONArray("media_attachments")
        require(attachments.length() == 1) { "Supply a post with one attachment." }
        val original = attachments.getJSONObject(0).getString("url")
        val expected = read(URL(original), ::sha256)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("evasi", Context.MODE_PRIVATE)
        val previous = store.all["share_time"] as? Long
        val share = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Look at this post! $url #media")
        }
        var output: Uri? = null
        try {
            ActivityScenario.launch<MainActivity>(share).use { scenario ->
                lateinit var model: DownloadModel
                scenario.onActivity { model = ViewModelProvider(it)[DownloadModel::class.java] }
                val finished = runBlocking { withTimeout(180_000) {
                    model.session.state.first { it.phase == Phase.Saved || it.phase == Phase.Failed }
                } }
                assertEquals("Download failed with " + finished.error, Phase.Saved, finished.phase)
                assertEquals(1, finished.savedCount)
                val uri = Uri.parse(requireNotNull(model.session.galleryUri))
                output = uri
                val resolver = context.contentResolver
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE),
                    null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                    assertTrue(cursor.getLong(1) > 0)
                }
                assertArrayEquals(expected, requireNotNull(resolver.openInputStream(uri)).use(::sha256))
            }
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            val editor = store.edit()
            if (previous == null) editor.remove("share_time") else editor.putLong("share_time", previous)
            editor.commit()
        }
    }

    private fun <T> read(url: URL, block: (InputStream) -> T): T = url.openConnection().apply {
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "EvasiAndroidLiveTest/1.0")
    }.getInputStream().use(block)

    private fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return digest.digest()
            digest.update(buffer, 0, count)
        }
    }
}
