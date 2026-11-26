package com.evasi.downloader

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import com.evasi.downloader.share.CompletedShare
import com.evasi.downloader.ui.QueueScreen
import com.evasi.downloader.ui.HistoryScreen
import com.evasi.downloader.ui.PreferencesScreen
import com.evasi.downloader.ui.DownloaderApp
import com.evasi.downloader.ui.Source
import com.evasi.downloader.ui.UiEvent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<DownloadModel>()
    private val clipboard by lazy { requireNotNull(getSystemService(ClipboardManager::class.java)) }
    private var shareForeground = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Preferences.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, 0x6625223D)
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            receive(intent)
        } else {
            shareForeground = savedInstanceState?.getBoolean("share_foreground") ?: false
        }
        setContent {
            val state by model.session.state.collectAsStateWithLifecycle()
            val settings by model.settings.collectAsStateWithLifecycle()
            DownloaderApp(state, settings.appearance,
                downloads = { QueueScreen(model.queue) },
                history = { HistoryScreen(model.queue) },
                settings = {
                    PreferencesScreen(settings, ::changeSettings,
                        onNotificationSettings = { onUiEvent(UiEvent.NotificationSettings) },
                        onClearHistory = { model.queue.clearHistory() },
                        onClearCache = { clearPreviewCache(applicationContext) })
                },
                postText = { CompletedShare(state.savedUris, state.description) },
                onEvent = ::onUiEvent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        receive(intent)
        if (!shareForeground && hasWindowFocus()) readClipboard(manual = false)
    }

    private fun receive(incoming: Intent) {
        shareForeground = incoming.action == Intent.ACTION_SEND || incoming.action == Intent.ACTION_SEND_MULTIPLE
        if (!shareForeground) return
        model.prefs.markShared(System.currentTimeMillis())
        val value = try { sharedInput(incoming) } catch (_: RuntimeException) { Incoming.Invalid }
        model.session.accept(value, Source.Share)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !shareForeground) readClipboard(manual = false)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) shareForeground = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("share_foreground", shareForeground)
        super.onSaveInstanceState(outState)
    }

    internal fun readClipboard(manual: Boolean) {
        val record = model.prefs.clipboard
        val clip = try {
            if (!manual) {
                val description = clipboard.primaryClipDescription
                if (description?.isSensitive() == true) return
                val stamp = description?.timestamp ?: 0L
                if (stamp > 0 && (stamp <= record.sharedAt || stamp == record.lastTimestamp)) return
            }
            // The clip may change between the description and content reads.
            clipboard.primaryClip?.also { if (!manual && it.description.isSensitive()) return }
        } catch (_: RuntimeException) { null }
        val link = clip?.let {
            SharedLinks.extractFrom(it.items().flatMap { item -> sequenceOf(item.text, item.uri?.toString()) })
        }
        if (link == null) {
            if (manual) toast(R.string.paste_missing)
            return
        }
        val timestamp = clip?.description?.timestamp ?: 0L
        if (manual || record.isNew(link, timestamp)) {
            model.prefs.markClipboard(link, timestamp)
            model.session.accept(Incoming.Link(link), Source.Clipboard)
        }
    }

    private fun changeSettings(transform: (AppSettings) -> AppSettings) {
        lifecycleScope.launch {
            var policyChanged = false
            var language = model.prefs.language
            try {
                model.prefs.update { current ->
                    transform(current).also { next ->
                        policyChanged = current.wifiOnly != next.wifiOnly || current.concurrentDownloads != next.concurrentDownloads
                        language = next.language
                    }
                }
            } catch (_: java.io.IOException) {
                toast(R.string.pref_save_error)
                return@launch
            }
            if (policyChanged) model.queue.settingsChanged()
            if (resources.configuration.locales[0].language != language && !isFinishing && !isDestroyed) recreate()
        }
    }

    private fun onUiEvent(event: UiEvent) {
        when (event) {
            UiEvent.Paste -> readClipboard(manual = true)
            UiEvent.OpenGallery -> model.session.galleryUri?.let { saved ->
                lifecycleScope.launch {
                    val viewer = viewMedia(this@MainActivity, Uri.parse(saved))
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                    if (viewer == null) toast(R.string.gallery_unavailable)
                    else try {
                        startActivity(viewer)
                    } catch (_: android.content.ActivityNotFoundException) {
                        toast(R.string.gallery_unavailable)
                    } catch (_: SecurityException) {
                        toast(R.string.gallery_unavailable)
                    }
                }
            }
            UiEvent.NotificationSettings -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("asked", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply()
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
            is UiEvent.SetLanguage -> if (event.tag != model.prefs.language) {
                model.handle(event)
                recreate()
            }
            else -> model.handle(event)
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

// The framework's sensitive-clip extras key can also be set by senders on API 29–32.
private fun ClipDescription.isSensitive() = extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
