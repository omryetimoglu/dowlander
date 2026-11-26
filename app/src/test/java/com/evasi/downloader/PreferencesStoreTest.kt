package com.evasi.downloader

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.evasi.downloader.media.DEFAULT_ALBUM
import com.evasi.downloader.ui.parseAvailableVersion
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PreferencesStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun withStore(file: File, block: suspend (Preferences) -> Unit) {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            migrations = listOf(legacySettingsMigration(app)),
            produceFile = { file },
        )
        try { block(Preferences(app, dataStore)) } finally { job.cancelAndJoin() }
    }

    @Test fun migrationPreservesExplicitChoicesAndRereadingDiskKeepsIndependentConcurrentEdits() = runBlocking {
        val legacy = app.getSharedPreferences("evasi", Context.MODE_PRIVATE)
        legacy.edit().putString("language", "tr").putBoolean("auto_start", true).commit()
        val file = File(files.root, "existing.preferences_pb")
        withStore(file) { prefs ->
            val initial = prefs.settings.first()
            assertEquals("tr", initial.language)
            assertTrue(initial.autoStart)
            coroutineScope {
                launch { prefs.update { it.copy(album = "My Media", concurrentDownloads = 3) } }
                launch { prefs.update { it.copy(autoStart = false, wifiOnly = true) } }
                launch { prefs.update { it.copy(appearance = it.appearance.copy(theme = "dark", glass = false)) } }
            }
        }
        withStore(file) { prefs ->
            val result = prefs.settings.first()
            assertEquals("tr", result.language)
            assertFalse(result.autoStart)
            assertEquals("My Media", result.album)
            assertEquals(3, result.concurrentDownloads)
            assertTrue(result.wifiOnly)
            assertEquals("dark", result.appearance.theme)
            assertFalse(result.appearance.glass)
            assertFalse(prefs.autoStart)
        }
    }

    @Test fun firstInstallUsesEnglishAndOptInDownloadsAndRejectsUnsafeFolderValues() = runBlocking {
        val file = File(files.root, "fresh.preferences_pb")
        withStore(file) { prefs ->
            val first = prefs.settings.first()
            assertEquals("en", first.language)
            assertFalse(first.autoStart)
            assertEquals(1, first.concurrentDownloads)
            assertEquals(0, first.defaultQuality)
            assertEquals(DEFAULT_ALBUM, first.album)
            prefs.update { it.copy(album = "../private", concurrentDownloads = 10, defaultQuality = -1) }
            assertEquals(DEFAULT_ALBUM, prefs.settings.first().album)
            assertEquals(3, prefs.settings.first().concurrentDownloads)
            assertEquals(0, prefs.settings.first().defaultQuality)
        }
    }

    @Test fun updateCheckUsesVersionCodeAndRequiresAnActualVersionDeclaration() {
        val version = parseAvailableVersion("defaultConfig {\n  versionCode = 14\n  versionName = \"2.0.1\"\n}")
        assertEquals(14, version?.code)
        assertEquals("2.0.1", version?.name)
        assertNull(parseAvailableVersion("versionName = \"2.0.1\""))
    }
}
