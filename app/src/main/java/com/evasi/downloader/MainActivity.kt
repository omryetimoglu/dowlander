package com.evasi.downloader

import android.Manifest
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.evasi.downloader.ui.DownloaderApp
import com.evasi.downloader.ui.Phase
import com.evasi.downloader.ui.Source
import com.evasi.downloader.ui.UiEvent

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
            val state by model.session.state.collectAsState()
            DownloaderApp(state, ::onUiEvent)
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
        shareForeground = incoming.action == Intent.ACTION_SEND
        if (!shareForeground) return
        model.prefs.markShared(System.currentTimeMillis())
        val value = runCatching { sharedInput(incoming) }.getOrDefault(Incoming.Invalid)
        model.session.accept(value, Source.Share)
    }

    @Suppress("DEPRECATION")
    private fun sharedInput(intent: Intent): Incoming {
        val payload = intent.clipData
        val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val clipItems = (0 until (payload?.itemCount ?: 0)).map { payload!!.getItemAt(it) }
        val uri = (listOf(stream, intent.data) + clipItems.map { it.uri }).firstOrNull { it?.scheme == "content" }
        if (uri != null) return Incoming.File(uri.toString(), intent.type)
        val texts = listOf(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.dataString) +
            clipItems.flatMap { listOf(it.text, it.uri?.toString()) } + intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val link = SharedLinks.extract(texts.filterNotNull().joinToString(" "))
        return link?.let(Incoming::Link) ?: Incoming.Invalid
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

    private fun readClipboard(manual: Boolean) {
        val state = model.session.state.value
        if (!manual && state.phase == Phase.Downloading) return
        val record = model.prefs.clipboard
        val clip = runCatching {
            val stamp = clipboard.primaryClipDescription?.timestamp
            if (!manual && stamp != null && stamp > 0 &&
                (stamp <= record.sharedAt || stamp == record.lastTimestamp)) return
            clipboard.primaryClip
        }.getOrNull()
        val link = clip?.let {
            val text = (0 until it.itemCount).map { index -> it.getItemAt(index) }
                .flatMap { item -> listOfNotNull(item.text, item.uri?.toString()) }
                .joinToString(" ")
            SharedLinks.extract(text)
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

    private fun onUiEvent(event: UiEvent) {
        when (event) {
            UiEvent.Paste -> readClipboard(manual = true)
            UiEvent.OpenGallery -> model.session.galleryUri?.let {
                runCatching { startActivity(viewMedia(this, Uri.parse(it))) }
                    .onFailure { toast(R.string.gallery_unavailable) }
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
