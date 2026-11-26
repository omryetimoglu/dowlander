package com.evasi.downloader

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import org.junit.Assert.assertNull
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

    @Test fun languageSelectionAndColdAndWarmShareEntryPointsInOneAppProcess() {
        // These Activity launches share the same Application and process-scoped AndroidViewModel factory.
        verifyFirstLaunchAndLanguagePersistence()
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
