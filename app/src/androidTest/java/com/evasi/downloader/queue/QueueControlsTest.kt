package com.evasi.downloader.queue

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.evasi.downloader.AppSettings
import com.evasi.downloader.Incoming
import com.evasi.downloader.media.FailureReason
import com.evasi.downloader.media.MediaFailure
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueControlsTest {
    @Test fun duplicateOrderPauseResumeRetryCancelAndConcurrentLimitsUseRealWorkManager() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val db = Room.inMemoryDatabaseBuilder(app, QueueDatabase::class.java).build()
        val settings = MutableStateFlow(AppSettings(wifiOnly = true))
        val tag = "queue-control-${UUID.randomUUID()}"
        val manager = WorkManager.getInstance(app)
        // The real scheduler accepts each request, but the delay keeps it offline during the assertions.
        val queue = DownloadQueue(app, db, settings) {
            OneTimeWorkRequestBuilder<DownloadWorker>().setInitialDelay(1, TimeUnit.DAYS).addTag(tag)
        }
        suspend fun row(id: String) = requireNotNull(db.queue().job(id))
        suspend fun assigned() = db.queue().schedulable().filter { it.lease != null }.map { it.id }
        suspend fun info(lease: String) = requireNotNull(manager.getWorkInfoByIdFlow(UUID.fromString(lease)).first())
        try {
            val a = queue.enqueue(Incoming.Link("https://x.com/i/status/12345678901"))
            val b = queue.enqueue(Incoming.Link("https://x.com/i/status/12345678902"))
            val c = queue.enqueue(Incoming.Link("https://x.com/i/status/12345678903"))
            assertEquals(a, queue.enqueue(Incoming.Link("https://x.com/alternate/status/12345678901?t=variant")))
            assertEquals(3, queue.jobs.first().size)
            assertEquals(listOf(a), assigned())
            val original = requireNotNull(row(a).lease)
            assertTrue(info(original).tags.contains("wifi-only"))

            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val staleWorker = launch {
                    queue.perform(a, original) {
                        entered.complete(Unit)
                        release.await()
                        throw MediaFailure(FailureReason.TIMEOUT)
                    }
                }
                withTimeout(10_000) { entered.await() }
                assertEquals(QueueState.DOWNLOADING, row(a).state)
                queue.pause(a)
                release.complete(Unit)
                staleWorker.join()
            }
            assertEquals(QueueState.PAUSED, row(a).state)
            assertNull(row(a).error)
            assertEquals(WorkInfo.State.CANCELLED, info(original).state)
            assertEquals(listOf(b), assigned())
            queue.move(c, -1)
            assertEquals(listOf(c), assigned())
            assertEquals(listOf(a, c, b), queue.jobs.first().map { it.id })
            queue.resume(a)
            assertEquals(QueueState.QUEUED, row(a).state)

            settings.value = AppSettings(wifiOnly = false, concurrentDownloads = 2)
            queue.settingsChanged()
            assertEquals(listOf(a, c), assigned())
            val attempt = requireNotNull(row(a).lease)
            assertFalse(info(attempt).tags.contains("wifi-only"))
            // Fail before network I/O. This exercises the same durable failure and slot handoff as the worker.
            queue.perform(a, attempt) { throw MediaFailure(FailureReason.TIMEOUT) }
            assertEquals(QueueState.FAILED, row(a).state)
            assertEquals("TIMEOUT", row(a).error)
            assertEquals(listOf(c, b), assigned())
            queue.retry(a)
            assertEquals(QueueState.QUEUED, row(a).state)
            assertNull(row(a).error)
            queue.cancel(c)
            assertEquals(QueueState.CANCELLED, row(c).state)
            assertEquals(listOf(a, b), assigned())
            assertNotEquals(attempt, row(a).lease)
            settings.value = AppSettings(concurrentDownloads = 1)
            queue.settingsChanged()
            assertEquals(listOf(a), assigned())
        } finally {
            manager.cancelAllWorkByTag(tag).await()
            db.close()
        }
    }
}
