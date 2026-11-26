package com.evasi.downloader

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.evasi.downloader.ui.Phase
import com.evasi.downloader.ui.ScreenState

internal fun viewMedia(context: Context, uri: Uri) = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, context.contentResolver.getType(uri))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal class DownloadNotifications(private val app: Context) {
    private val manager = requireNotNull(app.getSystemService(NotificationManager::class.java))
    private var showingProgress = false
    private var last: Snapshot? = null
    private data class Snapshot(val post: Long, val phase: Phase, val item: Int, val percent: Int,
        val resolving: Boolean, val language: String)

    fun update(state: ScreenState, published: String?) {
        if (state.phase != Phase.Downloading && state.phase != Phase.Saved) {
            clearProgress()
            last = null
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        val percent = state.progress?.let { (it * 20).toInt() * 5 } ?: -1
        val snapshot = Snapshot(state.controlsGeneration, state.phase, state.item, percent, state.resolving, state.language)
        if (snapshot == last) return
        last = snapshot
        val context = Preferences.localized(app)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val home = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(app, 0, home, flags))
        if (state.phase == Phase.Saved && published != null) {
            clearProgress()
            val gallery = PendingIntent.getActivity(app, 1, viewMedia(app, Uri.parse(published)), flags)
            val action = Notification.Action.Builder(null, context.getString(R.string.notification_open), gallery).build()
            builder.setContentText(context.getString(R.string.notification_saved)).setAutoCancel(true)
                .setContentIntent(gallery).addAction(action)
            manager.notify(2, builder.build())
        } else {
            showingProgress = true
            val message = if (state.resolving) R.string.notification_preparing else R.string.notification_progress
            builder.setContentText(context.getString(message)).setOngoing(true)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            if (state.total > 1 && state.item > 0) builder.setSubText(context.getString(R.string.item_progress, state.item, state.total))
            manager.notify(1, builder.build())
        }
    }

    fun clearProgress() {
        if (showingProgress) { manager.cancel(1); showingProgress = false }
    }

    companion object { private const val CHANNEL = "media_downloads" }
}
