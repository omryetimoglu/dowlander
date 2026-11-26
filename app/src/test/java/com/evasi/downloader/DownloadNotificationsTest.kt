package com.evasi.downloader

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.evasi.downloader.ui.Phase
import com.evasi.downloader.ui.ScreenState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNotificationsTest {
    @Test fun permissionGrantAndAlbumProgressAreReflectedInTheNotification() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        var granted = false
        var permissionChecks = 0
        val context = object : ContextWrapper(app) {
            override fun checkSelfPermission(permission: String): Int {
                if (permission != Manifest.permission.POST_NOTIFICATIONS) return super.checkSelfPermission(permission)
                permissionChecks++
                return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
        }
        val manager = requireNotNull(app.getSystemService(NotificationManager::class.java))
        val notifications = DownloadNotifications(context)
        val progress = ScreenState(phase = Phase.Downloading, item = 1, total = 2, progress = 0.11f)
        notifications.update(progress, null)
        assertTrue(manager.activeNotifications.isEmpty())

        granted = true
        notifications.update(progress, null)
        assertEquals(10, manager.activeNotifications.single().notification.extras.getInt(Notification.EXTRA_PROGRESS))
        notifications.update(progress.copy(progress = 0.12f), null)
        assertEquals(2, permissionChecks)
        notifications.update(progress.copy(item = 2, language = "tr", progress = 0.51f), null)
        val extras = manager.activeNotifications.single().notification.extras
        assertEquals(50, extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(Preferences.localized(app, "tr").getString(R.string.item_progress, 2, 2),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        notifications.update(progress.copy(phase = Phase.Cancelled), null)
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.update(progress.copy(phase = Phase.Saved), "content://unavailable/item/1")
        assertEquals(2, manager.activeNotifications.single().id)
        assertTrue(manager.activeNotifications.single().notification.actions.isNullOrEmpty())
    }
}
