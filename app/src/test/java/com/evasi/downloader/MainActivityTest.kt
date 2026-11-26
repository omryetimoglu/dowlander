package com.evasi.downloader

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.evasi.downloader.ui.Phase
import com.evasi.downloader.ui.Source
import com.evasi.downloader.ui.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "tr")
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainActivityTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun pauseBackgroundWork() {
        // Inspect the state accepted by each real Activity entry point before its network job starts.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun restoreDispatcher() = Dispatchers.resetMain()

    private fun verifySensitiveClipRequiresManualPaste() {
        val link = "https://mastodon.social/@example/11?share=abc"
        val clip = ClipData.newPlainText("link", link)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        requireNotNull(app.getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[DownloadModel::class.java].session
            activity.onWindowFocusChanged(true)
            assertEquals(Source.None, session.state.value.source)
            activity.readClipboard(manual = true)
            assertEquals(link, session.state.value.link)
            assertEquals(Source.Clipboard, session.state.value.source)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun migratesLastLinkWithoutKeepingItsQueryOrLosingDeduplication() {
        val link = "https://example.org/first?share=private"
        val store = app.getSharedPreferences("evasi", Application.MODE_PRIVATE)
        store.edit().putString("last_link", link).putLong("last_clip_time", 0L).apply()
        val prefs = Preferences(app)
        assertFalse(store.contains("last_link"))
        assertFalse(prefs.clipboard.isNew(link, 0L))
        assertTrue(prefs.clipboard.isNew(link, 2L))
        val next = "https://example.org/next?share=other"
        prefs.markClipboard(next, 0L)
        assertFalse(Preferences(app).clipboard.isNew(next, 0L))
        assertTrue(store.all.values.none { it == link || it == next })
    }

    @Test fun shareValidationMatchesDeclaredMediaAndRejectsIncompleteBatches() {
        val post = "https://mastodon.social/@example/123"
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, post)
        assertEquals(Incoming.Link(post), sharedInput(text))
        val preview = ClipData.newRawUri("thumbnail", Uri.parse("content://preview/photo/1"))
        text.clipData = preview
        assertEquals(Incoming.Link(post), sharedInput(text))
        assertEquals(Incoming.Invalid, sharedInput(Intent(Intent.ACTION_SEND).setType("text/plain").apply { clipData = preview }))
        assertEquals(Incoming.Invalid, sharedInput(text.setType("application/pdf")))
        val one = Uri.parse("content://photos/one")
        val two = Uri.parse("content://photos/two")
        fun batch(vararg uris: Uri) = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
        assertEquals(Incoming.Files(listOf(one.toString(), two.toString()), "*/*"), sharedInput(batch(one, two, one)))
        assertEquals(Incoming.Invalid, sharedInput(batch(one, Uri.parse("file:///sdcard/photo.jpg"))))
        assertEquals(Incoming.Invalid, sharedInput(batch(Uri.parse("content:///one"))))
    }

    @Test fun languageSelectionClipboardAndShareEntryPointsInOneAppProcess() {
        // Robolectric replaces Application between test methods. AndroidX caches its
        // AndroidViewModelFactory per process, so launch these Activities in one app lifetime.
        verifyFirstLaunchAndLanguagePersistence()
        verifySensitiveClipRequiresManualPaste()
        verifyColdAndWarmShares()
    }

    private fun verifyFirstLaunchAndLanguagePersistence() {
        assertEquals("Ayarlar", app.getString(R.string.settings))
        val first = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = first.get()
            assertEquals("Settings", activity.getString(R.string.settings))
            assertEquals("Select quality", activity.getString(R.string.select_quality))
            val model = ViewModelProvider(activity)[DownloadModel::class.java]
            model.handle(UiEvent.SetLanguage("tr"))
            assertEquals("tr", model.session.state.value.language)
        } finally {
            first.pause().stop().destroy()
        }

        assertEquals("tr", Preferences(app).language)
        val next = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = next.get()
            assertEquals("Ayarlar", activity.getString(R.string.settings))
            assertEquals("Piksel seç", activity.getString(R.string.select_quality))
        } finally {
            next.pause().stop().destroy()
        }
    }

    private fun verifyColdAndWarmShares() {
        val copied = "https://mastodon.social/@example/11"
        val firstShare = "https://mastodon.social/@example/22"
        val nextShare = "https://mastodon.social/@example/33"
        val clipboard = requireNotNull(app.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("link", copied))
        Preferences(app).autoStart = false

        fun share(link: String) = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "#example $link")
        val controller = Robolectric.buildActivity(MainActivity::class.java, share(firstShare)).create().start().resume()
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[DownloadModel::class.java].session
            activity.onWindowFocusChanged(true)
            val first = session.state.value
            assertEquals(firstShare, first.link)
            assertEquals(Source.Share, first.source)
            assertEquals(Phase.Downloading, first.phase)
            assertNull(first.countdown)

            controller.newIntent(share(nextShare))
            activity.onWindowFocusChanged(true)
            val next = session.state.value
            assertEquals(nextShare, next.link)
            assertEquals(Source.Share, next.source)
            assertEquals(Phase.Downloading, next.phase)
            assertEquals(first.controlsGeneration + 1, next.controlsGeneration)
            controller.newIntent(share(nextShare))
            assertEquals(next.controlsGeneration, session.state.value.controlsGeneration)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
