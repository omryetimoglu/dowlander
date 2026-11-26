package com.evasi.downloader.share

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.evasi.downloader.R
import com.evasi.downloader.auth.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w393dp-h851dp-port")
@ConscryptMode(ConscryptMode.Mode.OFF)
class SharingUiTest {
    @get:Rule val ui = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uris = listOf("content://media/external/video/media/1")
    private val caption = "Original post caption"

    @After fun stopSession() { scope.cancel() }

    @Test fun guestCanShareFileAndSeesSignInPromptBeforeEditingText() {
        val account = account(null, configured = false)
        ui.setContent { CompletedShare(uris, caption, account = account) }
        ui.onNodeWithText(app.getString(R.string.share_media_button)).assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithText(caption).assertDoesNotExist()
        ui.onNodeWithText(app.getString(R.string.share_with_text)).performClick()
        ui.onNodeWithText(app.getString(R.string.x_login)).assertIsDisplayed()
        ui.onNodeWithText(app.getString(R.string.x_login_unconfigured)).assertIsDisplayed()
    }

    @Test fun authenticatedUserCanEditTextAndLogoutRemovesDraft() {
        val account = account(XGrant("tester", "test-only", System.currentTimeMillis() + 120_000))
        runBlocking { account.authorized() }
        ui.setContent { CompletedShare(uris, caption, account = account) }
        ui.onNodeWithText(caption).assertIsDisplayed()
        ui.onNode(hasSetTextAction()).performTextReplacement("Edited text")
        ui.onNodeWithText(app.getString(R.string.share_with_text)).assertIsEnabled()
        ui.onNodeWithText(app.getString(R.string.share_copy_text)).assertIsEnabled()
        ui.onNodeWithText(app.getString(R.string.share_video_only)).assertIsEnabled()
        runBlocking { account.logout() }
        ui.waitUntil { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("Edited text").assertDoesNotExist()
        ui.onNodeWithText(app.getString(R.string.share_media_button)).assertIsDisplayed()
    }

    private fun account(grant: XGrant?, configured: Boolean = true): XSession {
        val store = object : XStore {
            private var record = LoginRecord(grant = grant)
            override fun read() = record
            override fun write(record: LoginRecord) { this.record = record }
        }
        val api = object : XApi {
            override fun verifyGrant(config: XConfig, pending: XPending, code: String): XGrant = error("Unexpected login")
            override fun revoke(config: XConfig, token: String) = Unit
        }
        return XSession(XConfig(if (configured) "public" else "", "com.evasi.downloader://oauth/x"), store, api, scope)
    }
}
