package com.evasi.downloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.evasi.downloader.R
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

abstract class ClipboardControlsChecks {
    @get:Rule val ui = createComposeRule()

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun fingerDownDisarmsBeforeReleaseWhileQualitiesAreLoading() {
        val generation = 17L
        var state by mutableStateOf(ScreenState(phase = Phase.Preparing, source = Source.Clipboard,
            resolving = true, controlsGeneration = generation))
        val events = CopyOnWriteArrayList<UiEvent>()

        ui.setContent {
            DownloaderApp(state) { event ->
                events += event
                state = when (event) {
                    UiEvent.OpenQuality -> state.copy(qualitySheet = true)
                    UiEvent.CloseQuality -> state.copy(qualitySheet = false)
                    is UiEvent.SelectQuality -> state.copy(quality = event.value, qualitySheet = false)
                    else -> state
                }
            }
        }
        ui.waitUntil(5_000) { UiEvent.ControlsReady(generation) in events }
        ui.onNodeWithText(label(R.string.start_download)).assertIsDisplayed().assertHasClickAction()
        val left = ui.onNodeWithText(label(R.string.select_quality))
        left.assertIsDisplayed().assertHasClickAction().performTouchInput { down(center) }
        ui.runOnIdle {
            assertTrue(UiEvent.ControlsReady(generation) in events)
            assertTrue(UiEvent.QualityTouched in events)
            assertFalse(UiEvent.OpenQuality in events)
        }
        left.performTouchInput { advanceEventTime(1600); up() }
        ui.onNodeWithText(label(R.string.quality_finding)).assertIsDisplayed()
        ui.runOnIdle {
            state = state.copy(phase = Phase.Ready, resolving = false, qualityOptions = listOf("1280 × 720"))
        }
        ui.onNodeWithText("1280 × 720").performClick()
        ui.onNodeWithText("1280 × 720").assertIsDisplayed().assertHasClickAction()
        ui.runOnIdle { assertFalse(UiEvent.Start in events) }
    }

    @Test
    fun leavingForSettingsMakesTheClipboardControlsUnavailable() {
        val generation = 9L
        val events = CopyOnWriteArrayList<UiEvent>()
        ui.setContent {
            DownloaderApp(ScreenState(phase = Phase.Ready, source = Source.Clipboard, controlsGeneration = generation)) {
                events += it
            }
        }
        ui.onNodeWithText(label(R.string.start_download)).assertIsDisplayed()
        ui.waitUntil(5_000) { UiEvent.ControlsReady(generation) in events }
        ui.onNodeWithContentDescription(label(R.string.settings)).performClick()
        ui.onNodeWithText(label(R.string.auto_start)).assertIsDisplayed()
        ui.runOnIdle { assertTrue(UiEvent.ControlsHidden(generation) in events) }
    }
}
