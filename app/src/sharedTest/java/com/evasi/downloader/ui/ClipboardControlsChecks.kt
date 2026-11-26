package com.evasi.downloader.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.evasi.downloader.R
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
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
        ui.waitUntil(5_000) { events.any { it is UiEvent.ControlsReady && it.generation == generation } }
        ui.onNodeWithText(label(R.string.start_download)).assertIsDisplayed().assertHasClickAction()
        val left = ui.onNodeWithText(label(R.string.select_quality))
        left.assertIsDisplayed().assertHasClickAction().performTouchInput { down(center) }
        ui.runOnIdle {
            assertTrue(events.any { it is UiEvent.ControlsReady && it.generation == generation })
            assertTrue(UiEvent.QualityTouched in events)
            assertFalse(UiEvent.OpenQuality in events)
        }
        left.performTouchInput { advanceEventTime(1600); up() }
        ui.onNodeWithText(label(R.string.quality_finding)).assertIsDisplayed()
        ui.runOnIdle {
            state = state.copy(phase = Phase.Ready, resolving = false, qualityOptions = listOf("1280 × 720"))
        }
        val resolution = hasText("1280 × 720")
        ui.onNode(resolution and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performSemanticsAction(SemanticsActions.OnClick)
        ui.runOnIdle { assertTrue(UiEvent.SelectQuality("1280 × 720") in events) }
        val selected = ui.onNode(resolution and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsDisplayed().assertHasClickAction()
        assertEquals(label(R.string.select_quality), selected.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        ui.runOnIdle { assertFalse(UiEvent.Start in events) }
    }

    @Test
    fun controlsUseAccessibilityTimeoutForInteractiveContent() {
        val events = CopyOnWriteArrayList<UiEvent>()
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long, containsIcons: Boolean, containsText: Boolean, containsControls: Boolean,
            ): Long = if (containsControls && containsText) 10_000L else originalTimeoutMillis
        }
        ui.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                DownloaderApp(ScreenState(phase = Phase.Ready, source = Source.Clipboard, controlsGeneration = 9)) {
                    events += it
                }
            }
        }
        ui.waitUntil(5_000) { UiEvent.ControlsReady(9, 10_000) in events }
        ui.onNodeWithText(label(R.string.select_quality)).assertIsDisplayed().performClick()
        ui.runOnIdle { assertTrue(UiEvent.OpenQuality in events) }
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
        ui.waitUntil(5_000) { events.any { it is UiEvent.ControlsReady && it.generation == generation } }
        ui.onNodeWithContentDescription(label(R.string.settings)).performClick()
        ui.onNodeWithText(label(R.string.auto_start)).assertIsDisplayed()
        ui.runOnIdle { assertTrue(UiEvent.ControlsHidden(generation) in events) }
    }
}
