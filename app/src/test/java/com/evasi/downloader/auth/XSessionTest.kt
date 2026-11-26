package com.evasi.downloader.auth

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XSessionTest {
    private val config = XConfig("public", "com.evasi.downloader://oauth/x")
    private class Memory : XStore {
        var data = LoginRecord()
        var allowWrites = true
        override fun read() = data
        override fun write(record: LoginRecord) {
            if (!allowWrites) throw IOException("storage")
            data = record
        }
    }
    private class Api(private val now: () -> Long) : XApi {
        var checked = 0
        var allowLogin = true
        var revoked: String? = null
        override fun verifyGrant(config: XConfig, pending: XPending, code: String): XGrant {
            checked++
            check(allowLogin)
            return XGrant("tester", "test-only", now() + 5000)
        }
        override fun revoke(config: XConfig, token: String) {
            revoked = token
            throw IOException("offline")
        }
    }

    private fun TestScope.session(memory: Memory, api: Api, cfg: XConfig = config): XSession =
        XSession(cfg, memory, api, backgroundScope, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }

    private fun callback(state: String) = URI(config.redirect + "?state=" + state + "&code=code")

    @Test fun pendingSurvivesRestartAndSuccessfulCallbackCannotBeReplayed() = runTest {
        val memory = Memory()
        val api = Api { testScheduler.currentTime }
        val original = session(memory, api)
        assertFalse(original.authorized())
        assertNotNull(original.start())
        val address = callback(requireNotNull(memory.data.pending).state)
        val recreated = session(memory, api)
        recreated.complete(address)
        recreated.complete(address)
        assertTrue(recreated.authorized())
        assertEquals("tester", recreated.account.value.username)
        assertNull(memory.data.pending)
        assertEquals(1, api.checked)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(XAccountState(issue = LoginIssue.EXPIRED), recreated.account.value)
        assertFalse(recreated.authorized())
        assertNull(memory.data.grant)
    }

    @Test fun replacingRequestDenialAndAccountVerificationFailureAllKeepGuest() = runTest {
        val memory = Memory()
        val api = Api { testScheduler.currentTime }
        val auth = session(memory, api)
        auth.start()
        val old = callback(memory.data.pending!!.state)
        auth.start()
        auth.complete(old)
        assertEquals(0, api.checked)
        val state = memory.data.pending!!.state
        auth.complete(URI(config.redirect + "?state=" + state + "&error=access_denied"))
        assertEquals(LoginIssue.DENIED, auth.account.value.issue)
        assertFalse(auth.authorized())
        auth.start()
        api.allowLogin = false
        auth.complete(callback(memory.data.pending!!.state))
        assertEquals(LoginIssue.CONNECTION, auth.account.value.issue)
        assertFalse(auth.authorized())
        assertNull(memory.data.pending)
    }

    @Test fun logoutInvalidatesStoredSessionEvenWhenRemoteRevocationFails() = runTest {
        val memory = Memory().apply { data = LoginRecord(grant = XGrant("tester", "test-only", 10_000)) }
        val api = Api { testScheduler.currentTime }
        val auth = session(memory, api)
        assertTrue(auth.authorized())
        auth.logout()
        assertEquals("test-only", api.revoked)
        assertEquals(LoginRecord(), memory.data)
        assertFalse(auth.authorized())
        assertFalse(session(memory, api).authorized())
    }

    @Test fun wakeAfterDeepSleepRechecksWallClockBeforeCaptionActions() = runTest {
        val memory = Memory().apply { data = LoginRecord(grant = XGrant("tester", "test-only", 10_000)) }
        var wallClock = 2000L
        val auth = XSession(config, memory, Api { wallClock }, backgroundScope, StandardTestDispatcher(testScheduler)) { wallClock }
        assertTrue(auth.authorized())
        wallClock = 100_000
        assertFalse(auth.authorized())
        assertNull(memory.data.grant)
        assertEquals(LoginIssue.EXPIRED, auth.account.value.issue)
    }

    @Test fun unavailableConfigurationExpiredRestoresAndWriteFailureNeverAuthorize() = runTest {
        val memory = Memory().apply { data = LoginRecord(grant = XGrant("old", "test-only", 0)) }
        val api = Api { testScheduler.currentTime }
        val disabled = session(memory, api, XConfig("", ""))
        assertNull(disabled.start())
        assertFalse(disabled.authorized())
        val normal = session(memory, api)
        assertFalse(normal.authorized())
        assertEquals(LoginIssue.EXPIRED, normal.account.value.issue)
        memory.allowWrites = false
        assertNull(normal.start())
        assertEquals(LoginIssue.STORAGE, normal.account.value.issue)
        assertNull(memory.data.pending)
        assertEquals(0, api.checked)
    }
}
