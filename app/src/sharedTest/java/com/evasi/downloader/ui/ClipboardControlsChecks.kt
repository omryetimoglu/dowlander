package com.evasi.downloader.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.evasi.downloader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

abstract class ClipboardControlsChecks {
    @get:Rule val ui = createComposeRule()

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Composable
    private fun Host(state: ScreenState, appearance: AppAppearance = AppAppearance(), onEvent: (UiEvent) -> Unit) {
        DownloaderApp(state, appearance, downloads = { Text("Queue content") }, history = { Text("History content") },
            settings = { Text("Settings content") }, postText = { Text("Share media now") }, onEvent = onEvent)
    }

    @Test fun qualityTouchPausesAutomaticStartWhileMetadataIsLoading() {
        var state by mutableStateOf(ScreenState(phase = Phase.Preparing, source = Source.Clipboard, resolving = true))
        val events = CopyOnWriteArrayList<UiEvent>()
        ui.setContent {
            Host(state) { event ->
                events += event
                state = when (event) {
                    UiEvent.OpenQuality -> state.copy(qualitySheet = true)
                    UiEvent.CloseQuality -> state.copy(qualitySheet = false)
                    is UiEvent.SelectQuality -> state.copy(quality = event.value, qualitySheet = false)
                    else -> state
                }
            }
        }
        ui.onNodeWithText(label(R.string.start_download)).assertIsNotEnabled()
        val select = ui.onNodeWithText(label(R.string.select_quality)).assertIsDisplayed()
        select.performTouchInput { down(center) }
        ui.runOnIdle {
            assertTrue(UiEvent.QualityTouched in events)
            assertFalse(UiEvent.OpenQuality in events)
        }
        select.performTouchInput { advanceEventTime(1600); up() }
        ui.onNodeWithText(label(R.string.quality_finding)).assertIsDisplayed()
        ui.runOnIdle { state = state.copy(phase = Phase.Ready, resolving = false, qualityOptions = listOf("720p")) }
        ui.onNode(hasText("720p") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("720p").assertIsDisplayed().assertHasClickAction()
        ui.onNodeWithText(label(R.string.start_download)).assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(listOf(UiEvent.Start), events.filterIsInstance<UiEvent.Start>()) }
    }

    @Test fun manualEntryPasteAndClearEmitDistinctActions() {
        var state by mutableStateOf(ScreenState())
        val events = CopyOnWriteArrayList<UiEvent>()
        ui.setContent {
            Host(state) { event ->
                events += event
                if (event is UiEvent.EditLink) state = state.copy(link = event.text)
            }
        }
        val link = "https://example.org/post"
        ui.onNode(hasSetTextAction()).performTextReplacement(link)
        ui.onNodeWithContentDescription(label(R.string.clear_link)).performClick()
        ui.onNodeWithContentDescription(label(R.string.paste_link)).performClick()
        ui.runOnIdle {
            assertEquals(listOf(UiEvent.EditLink(link), UiEvent.EditLink(""), UiEvent.Paste), events.toList())
        }
    }

    @Test fun tabsExposeTheirScreensAndReturnToCurrentMedia() {
        ui.setContent { Host(ScreenState(phase = Phase.Saved, savedCount = 1), AppAppearance(theme = "dark")) {} }
        ui.onNodeWithText("Share media now").assertExists()
        for ((name, content) in listOf(R.string.tab_downloads to "Queue content", R.string.tab_history to "History content",
                R.string.settings to "Settings content")) {
            ui.onNodeWithContentDescription(label(name)).performClick()
            ui.onNodeWithText(content).assertIsDisplayed()
        }
        ui.onNodeWithContentDescription(label(R.string.tab_home)).performClick()
        ui.onNodeWithText("Share media now").assertExists()
    }
}
