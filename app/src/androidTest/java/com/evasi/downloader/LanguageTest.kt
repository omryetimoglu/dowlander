package com.evasi.downloader

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LanguageTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test
    fun firstLaunchUsesEnglishAndTurkishPersistsWhenTheActivityIsRelaunched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("evasi", Context.MODE_PRIVATE)
        val previousLanguage = store.getString("language", null)
        val previousShareTime = store.all["share_time"] as? Long
        // Keep existing clipboard contents out of a language-only test.
        store.edit().remove("language").putLong("share_time", Long.MAX_VALUE).commit()
        try {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("tr")) }
            val turkishContext = context.createConfigurationContext(config)
            assertEquals("Ayarlar", turkishContext.getString(R.string.settings))
            assertEquals("Settings", Preferences.localized(turkishContext).getString(R.string.settings))

            ActivityScenario.launch(MainActivity::class.java).use {
                ui.onNodeWithContentDescription("Settings").performClick()
                ui.onNodeWithText("Language").performClick()
                ui.onNodeWithText("Türkçe").performClick()
                ui.waitUntil(5_000) { ui.onAllNodesWithText("Ayarlar").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText("Ayarlar").assertIsDisplayed()
                assertEquals("tr", Preferences(context).language)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.onNodeWithContentDescription("Ayarlar").assertIsDisplayed()
            }
        } finally {
            val editor = store.edit()
            if (previousLanguage == null) editor.remove("language") else editor.putString("language", previousLanguage)
            if (previousShareTime == null) editor.remove("share_time") else editor.putLong("share_time", previousShareTime)
            editor.commit()
        }
    }
}
