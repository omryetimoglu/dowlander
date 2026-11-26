package com.evasi.downloader

import android.content.res.Configuration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LanguageTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test
    fun languageAndThemeChosenInSettingsPersistAcrossRelaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Preferences(context)
        val saved = runBlocking { prefs.settings.first() }
        val sharedAt = prefs.clipboard.sharedAt
        // Use the real settings transaction and restore state on long-lived test devices.
        runBlocking { prefs.update { it.copy(language = "en", autoStart = false, appearance = it.appearance.copy(theme = "light")) } }
        prefs.markShared(Long.MAX_VALUE)
        try {
            val turkishDevice = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("tr"))
            })
            assertEquals("Ayarlar", turkishDevice.getString(R.string.settings))
            assertEquals("Settings", Preferences.localized(turkishDevice).getString(R.string.settings))

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForTab("Settings")
                waitForTheme(scenario, light = true)
                ui.onNodeWithContentDescription("Settings").performClick()
                ui.onNodeWithText("Theme").performClick()
                ui.onNodeWithText("Dark").performClick()
                waitForTheme(scenario, light = false)
                ui.onNodeWithText("Language").performClick()
                ui.onNodeWithText("Türkçe").performClick()
                waitForTab("Ayarlar")
            }
            val chosen = runBlocking { prefs.settings.first() }
            assertEquals("tr", chosen.language)
            assertEquals("dark", chosen.appearance.theme)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForTab("Ayarlar")
                waitForTheme(scenario, light = false)
                ui.onNodeWithContentDescription("Ayarlar").performClick()
                ui.onNodeWithText("Koyu").assertIsDisplayed()
                ui.onNodeWithText("Türkçe").assertIsDisplayed()
            }
        } finally {
            runBlocking { prefs.update { it.copy(language = saved.language, autoStart = saved.autoStart,
                appearance = it.appearance.copy(theme = saved.appearance.theme)) } }
            prefs.markShared(sharedAt)
        }
    }

    private fun waitForTab(label: String) {
        ui.waitUntil(20_000) { ui.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription(label).assertIsDisplayed()
    }

    private fun waitForTheme(scenario: ActivityScenario<MainActivity>, light: Boolean) {
        ui.waitUntil(20_000) {
            var matches = false
            scenario.onActivity {
                matches = WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars == light
            }
            matches
        }
    }
}
