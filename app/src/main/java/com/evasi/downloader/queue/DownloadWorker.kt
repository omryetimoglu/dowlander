package com.evasi.downloader.queue

import android.Manifest
import android.app.Notification
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.evasi.downloader.MainActivity
import com.evasi.downloader.Preferences
import com.evasi.downloader.R
import com.evasi.downloader.viewMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(DownloadQueue.JOB) ?: return@withContext Result.failure()
        val queue = DownloadQueue.get(applicationContext)
        val alerts = TransferNotifications(Preferences.localized(applicationContext), jobId, id.hashCode())
        var progress: Job? = null
        var ordinaryWorker = false
        try {
            queue.perform(jobId, id.toString()) { row ->
                try {
                    setForeground(alerts.foreground(row))
                } catch (stop: CancellationException) {
                    throw stop
                } catch (failure: IllegalStateException) {
                    currentCoroutineContext().ensureActive()
                    // Android can deny a foreground-service promotion when Wi-Fi arrives in the background.
                    // WorkManager can still perform its regular timed work and retry from file checkpoints.
                    if (Build.VERSION.SDK_INT < 31 || failure !is ForegroundServiceStartNotAllowedException) throw failure
                    ordinaryWorker = true
                }
                progress = launch {
                    queue.watchJob(jobId).filterNotNull().map { it.progress() }.distinctUntilChanged()
                        .collect { if (it.state == QueueState.DOWNLOADING) alerts.update(it) }
                }
            }
        } finally {
            try { progress?.cancelAndJoin() } finally { if (ordinaryWorker) alerts.clearProgress() }
        }
        if (progress != null) {
            queue.watchJob(jobId).first()?.takeIf { it.state == QueueState.COMPLETED }?.let { alerts.completed(it) }
        }
        Result.success()
    }
}

private data class Progress(val state: QueueState, val item: Int, val count: Int, val percent: Int)
private fun QueueEntry.progress() = Progress(state, itemIndex + 1, itemCount, totalBytes?.takeIf { it > 0 }?.let {
    ((downloadedBytes.toDouble() / it * 20).toInt() * 5).coerceIn(0, 100)
} ?: -1)

private class TransferNotifications(val context: Context, jobId: String, attempt: Int) {
    private val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
    private val channel = "media_downloads"
    private val key = jobId.hashCode() and 0x3fffffff
    private val progressId = 0x40000000 or (attempt and 0x3fffffff)
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    init {
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW))
    }

    private fun base(): Notification.Builder {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name)).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent, flags))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
    }

    private fun progress(state: Progress): Notification {
        val builder = base().setContentText(context.getString(if (state.count == 0) R.string.notification_preparing
            else R.string.notification_progress)).setOngoing(true).setShowWhen(false)
            .setProgress(100, state.percent.coerceAtLeast(0), state.percent < 0)
        if (state.count > 1) builder.setSubText(context.getString(R.string.item_progress, state.item, state.count))
        return builder.build()
    }

    fun foreground(row: QueueEntry) = ForegroundInfo(progressId, progress(row.progress()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    fun clearProgress() = manager.cancel(progressId)

    fun update(value: Progress) = post(progressId, progress(value))

    suspend fun completed(row: QueueEntry) {
        val builder = base().setContentText(context.getString(R.string.notification_saved)).setAutoCancel(true)
        row.lastUri?.let { viewMedia(context, Uri.parse(it)) }?.let { intent ->
            val gallery = PendingIntent.getActivity(context, key, intent, flags)
            builder.setContentIntent(gallery)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.notification_open), gallery).build())
        }
        post(key, builder.build())
    }
}
