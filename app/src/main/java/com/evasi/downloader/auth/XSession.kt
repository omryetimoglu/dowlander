package com.evasi.downloader.auth

import android.content.Context
import com.evasi.downloader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

internal enum class LoginIssue { EXPIRED, DENIED, CONNECTION, STORAGE, BROWSER }
internal data class XAccountState(val username: String? = null, val checking: Boolean = false, val issue: LoginIssue? = null)

internal class XSession(
    val config: XConfig,
    private val store: XStore,
    private val api: XApi,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var record = LoginRecord()
    private var revision = 0L
    private var expiry: Job? = null
    private val state = MutableStateFlow(XAccountState())
    val account = state.asStateFlow()

    private val loaded = scope.async(io) {
        lock.withLock {
            if (!config.available) return@withLock
            try {
                record = store.read()
                val grant = record.grant
                if (grant != null && now() >= grant.expiresAt) {
                    expireIfNeeded()
                } else {
                    state.value = XAccountState(grant?.username)
                    scheduleExpiry()
                }
            } catch (_: Exception) {
                record = LoginRecord()
                state.value = XAccountState(issue = LoginIssue.STORAGE)
            }
        }
    }

    suspend fun start(): URI? = withContext(io) {
        loaded.await()
        lock.withLock {
            if (!config.available) return@withLock null
            val next = XProtocol.pending(now())
            revision++
            if (!persist(record.copy(pending = next))) return@withLock null
            state.value = XAccountState(record.grant?.username)
            XProtocol.authorize(config, next)
        }
    }

    suspend fun complete(callback: URI) = withContext(io) {
        loaded.await()
        val exchange = lock.withLock {
            val pending = record.pending ?: return@withContext
            val response = XProtocol.callback(config, pending, callback, now())
            if (response.result == CallbackResult.IGNORE) return@withContext
            if (!persist(record.copy(pending = null))) return@withContext
            if (response.result == CallbackResult.DENIED) {
                state.value = XAccountState(record.grant?.username, issue = LoginIssue.DENIED)
                return@withContext
            }
            state.value = XAccountState(record.grant?.username, checking = true)
            Triple(revision, pending, response.code)
        }
        val grant = try {
            api.verifyGrant(config, exchange.second, exchange.third)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            lock.withLock {
                if (revision == exchange.first) state.value = XAccountState(record.grant?.username, issue = LoginIssue.CONNECTION)
            }
            return@withContext
        }
        lock.withLock {
            if (revision != exchange.first) return@withLock
            if (!persist(LoginRecord(grant = grant))) return@withLock
            state.value = XAccountState(grant.username)
            scheduleExpiry()
        }
    }

    suspend fun authorized(): Boolean = withContext(io) {
        loaded.await()
        lock.withLock { expireIfNeeded(); record.grant != null }
    }

    suspend fun logout() = withContext(io) {
        loaded.await()
        val old = lock.withLock {
            revision++
            expiry?.cancel()
            val token = record.grant?.accessToken
            record = LoginRecord()
            if (persist(record)) state.value = XAccountState()
            token
        }
        if (old != null) try { api.revoke(config, old) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Local authorization has already ended. */ }
    }

    fun browserUnavailable() {
        state.value = state.value.copy(issue = LoginIssue.BROWSER)
    }

    private fun persist(next: LoginRecord): Boolean = try {
        store.write(next)
        record = next
        true
    } catch (_: Exception) {
        state.value = XAccountState(record.grant?.username, issue = LoginIssue.STORAGE)
        false
    }

    private fun expireIfNeeded() {
        val grant = record.grant ?: return
        if (now() < grant.expiresAt) return
        record = record.copy(grant = null)
        if (persist(record)) state.value = XAccountState(issue = LoginIssue.EXPIRED)
    }

    private fun scheduleExpiry() {
        expiry?.cancel()
        val grant = record.grant ?: return
        expiry = scope.launch(io) {
            var remaining = grant.expiresAt - now()
            while (remaining > 0) {
                delay(remaining)
                remaining = grant.expiresAt - now()
            }
            lock.withLock { expireIfNeeded() }
        }
    }
}

internal object XAccounts {
    // Verification outlives the short-lived browser callback Activity.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: XSession? = null
    @Synchronized fun get(context: Context): XSession = session ?: XSession(
        XConfig(BuildConfig.X_CLIENT_ID, BuildConfig.X_REDIRECT_URI),
        XVault(context.applicationContext), XHttpApi(), scope,
    ).also { session = it }

    fun receive(context: Context, callback: URI) {
        val account = get(context)
        scope.launch { account.complete(callback) }
    }
}
