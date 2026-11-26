package com.evasi.downloader

import com.evasi.downloader.media.*
import com.evasi.downloader.queue.QueueEntry
import com.evasi.downloader.queue.QueueState
import com.evasi.downloader.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSessionTest {
    private val a = Incoming.Link("https://mastodon.social/@sample/123")
    private val b = Incoming.Link("https://mastodon.social/@sample/456")
    private val high = Quality(1920, 1080)
    private val low = Quality(640, 360)
    private val info = MediaPost(listOf(MediaItem(MediaKind.VIDEO, listOf(
        MediaSource("https://cdn.example/a.mp4", quality = high, name = "movie", estimatedBytes = 2000),
        MediaSource("https://cdn.example/b.mp4", quality = low, estimatedBytes = 400),
    ))), "Post", "Original text")

    private inner class Backend : HomeBackend {
        var resolver: suspend (Incoming.Link) -> MediaPost = { info }
        var staging: suspend () -> Unit = {}
        val resolved = mutableListOf<Incoming>()
        val submissions = mutableListOf<Pair<Incoming, Quality?>>()
        val rows = MutableStateFlow<Map<String, QueueEntry>>(emptyMap())
        val retried = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override suspend fun resolve(input: Incoming.Link): MediaPost { resolved += input; return resolver(input) }
        override suspend fun enqueue(input: Incoming, quality: Quality?, post: MediaPost?): String {
            val id = (submissions.size + 1).toString()
            submissions += input to quality
            staging()
            rows.value += id to QueueEntry(id, id, "", true, id.toLong(), post?.title ?: "Shared", "Mastodon",
                (input as? Incoming.Link)?.url, album = "Tests", itemCount = post?.items?.size ?: 0)
            return id
        }
        override fun observe(id: String) = rows.mapNotNull { it[id] }
        override suspend fun outputs(id: String) = SavedOutput(listOf("content://media/video/$id"), "result.mp4", high, "video/mp4")
        override suspend fun retry(id: String) { retried += id }
        override suspend fun cancel(id: String) { cancelled += id }
        fun update(id: String, change: (QueueEntry) -> QueueEntry) { rows.value += id to change(rows.value.getValue(id)) }
    }
    private fun TestScope.session(backend: Backend, auto: Boolean = false) =
        DownloadSession(backgroundScope, backend, "en", auto, { testScheduler.currentTime })
    private fun TestScope.pass(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test fun defaultClipboardPreparesForMinimumTransitionAndWaitsForExplicitStart() = runTest {
        val backend = Backend()
        val session = session(backend)
        session.accept(a, Source.Clipboard)
        pass(1499)
        assertEquals(Phase.Preparing, session.state.value.phase)
        session.event(UiEvent.Start)
        pass(10001)
        assertEquals(Phase.Ready, session.state.value.phase)
        assertEquals(listOf("1080p", "360p"), session.state.value.qualityOptions)
        assertEquals(2000L, session.state.value.totalBytes)
        assertTrue(backend.submissions.isEmpty())
        session.event(UiEvent.OpenQuality)
        session.event(UiEvent.SelectQuality("360p"))
        assertEquals(400L, session.state.value.totalBytes)
        repeat(2) { session.event(UiEvent.Start) }
        runCurrent()
        assertEquals(listOf(a to low), backend.submissions)
        assertEquals(Phase.Queued, session.state.value.phase)
    }

    @Test fun manualTypingDebouncesResolvingAndClearDropsAnUnsubmittedDraft() = runTest {
        val backend = Backend()
        val session = session(backend)
        session.event(UiEvent.EditLink(a.url))
        pass(100)
        session.event(UiEvent.EditLink(b.url))
        pass(1500)
        assertEquals(listOf(b), backend.resolved)
        assertEquals(Source.Manual, session.state.value.source)
        assertEquals(Phase.Ready, session.state.value.phase)
        session.event(UiEvent.EditLink(""))
        assertEquals(Phase.Idle, session.state.value.phase)
        assertTrue(backend.submissions.isEmpty())
    }

    @Test fun automaticModeWaitsForSlowResolutionAndQueuesOriginalOnce() = runTest {
        val backend = Backend().apply { resolver = { delay(4000); info } }
        val session = session(backend, true)
        session.preferredSize = 360
        session.event(UiEvent.EditLink(a.url))
        pass(3000)
        assertEquals(Phase.Preparing, session.state.value.phase)
        assertTrue(backend.submissions.isEmpty())
        pass(1500)
        assertEquals(listOf(a to null), backend.submissions)
        assertEquals(Phase.Queued, session.state.value.phase)
    }

    @Test fun turningOffAutoOrSelectingQualityDuringPreparationKeepsUserInControl() = runTest {
        val backend = Backend()
        val session = session(backend, true)
        session.accept(a, Source.Clipboard)
        session.event(UiEvent.SetAutoStart(false))
        pass(1500)
        assertEquals(Phase.Ready, session.state.value.phase)
        session.event(UiEvent.SetAutoStart(true))
        session.accept(b, Source.Clipboard)
        session.event(UiEvent.QualityTouched)
        pass(1500)
        assertTrue(backend.submissions.isEmpty())
        session.event(UiEvent.SelectQuality("360p"))
        session.event(UiEvent.SelectOriginal)
        session.event(UiEvent.Start)
        runCurrent()
        assertEquals(listOf(b to null), backend.submissions)
    }

    @Test fun shareImmediatelyQueuesWithoutPreviewOrSecondTapAndSurvivesANewDraft() = runTest {
        val backend = Backend()
        val released = CompletableDeferred<Unit>()
        backend.staging = { released.await() }
        val session = session(backend)
        session.accept(a, Source.Share)
        session.accept(a, Source.Share)
        session.accept(b, Source.Share)
        runCurrent()
        assertEquals(listOf(a to null, b to null), backend.submissions)
        assertTrue(backend.resolved.isEmpty())
        released.complete(Unit)
        runCurrent()
        assertEquals(b.url, session.state.value.link)
        assertEquals(Phase.Queued, session.state.value.phase)
        assertEquals(2, backend.rows.value.size)
    }

    @Test fun staleResolverCannotPublishEvenIfItIgnoresCancellation() = runTest {
        val backend = Backend()
        val unblock = CompletableDeferred<Unit>()
        backend.resolver = { value ->
            if (value == a) withContext(NonCancellable) { unblock.await(); info.copy(title = "stale") }
            else info.copy(title = "new")
        }
        val session = session(backend, true)
        session.accept(a, Source.Clipboard)
        runCurrent()
        session.accept(b, Source.Clipboard)
        pass(1500)
        unblock.complete(Unit)
        runCurrent()
        assertEquals(listOf(b to null), backend.submissions)
        assertEquals("new", session.state.value.title)
    }

    @Test fun progressRetryAndCompletionComeFromTheSameDurableRow() = runTest {
        val backend = Backend()
        val session = session(backend)
        session.accept(a, Source.Share)
        runCurrent()
        backend.update("1") { it.copy(state = QueueState.DOWNLOADING, downloadedBytes = 25, totalBytes = 100) }
        runCurrent()
        assertEquals(0.25f, session.state.value.progress)
        backend.update("1") { it.copy(state = QueueState.FAILED, error = "TIMEOUT") }
        runCurrent()
        assertEquals(UiError.Timeout, session.state.value.error)
        session.event(UiEvent.Retry)
        runCurrent()
        assertEquals(listOf("1"), backend.retried)
        backend.update("1") { it.copy(state = QueueState.COMPLETED, savedCount = 1, downloadedBytes = 100, description = "hello") }
        runCurrent()
        assertEquals(listOf("content://media/video/1"), session.state.value.savedUris)
        assertEquals("result.mp4", session.state.value.fileName)
        assertEquals("1920 × 1080", session.state.value.resolution)
        assertEquals("hello", session.state.value.description)
        assertEquals(1, backend.submissions.size)
        val restored = session(backend)
        restored.restore("1")
        runCurrent()
        assertEquals(session.galleryUri, restored.galleryUri)
        assertEquals(Phase.Saved, restored.state.value.phase)
    }

    @Test fun cancellationWhileStagingStillCancelsThatJobAfterHomeChanges() = runTest {
        val backend = Backend()
        val unblock = CompletableDeferred<Unit>()
        backend.staging = { unblock.await() }
        val session = session(backend)
        session.accept(a, Source.Share)
        runCurrent()
        session.event(UiEvent.Cancel)
        session.event(UiEvent.EditLink(b.url))
        unblock.complete(Unit)
        pass(1500)
        assertEquals(listOf("1"), backend.cancelled)
        assertEquals(Phase.Ready, session.state.value.phase)
        assertTrue(session.state.value.savedUris.isEmpty())
    }

    @Test fun invalidAndUnsupportedInputsNeverReachQueue() = runTest {
        val backend = Backend().apply { resolver = { throw MediaFailure(FailureReason.UNSUPPORTED) } }
        val session = session(backend, true)
        session.event(UiEvent.EditLink("ordinary text"))
        pass(800)
        assertEquals(UiError.InvalidLink, session.state.value.error)
        session.event(UiEvent.EditLink("https://example.org/no-media"))
        pass(1500)
        assertEquals(UiError.Unsupported, session.state.value.error)
        session.accept(Incoming.Invalid, Source.Share)
        assertEquals(UiError.InvalidLink, session.state.value.error)
        assertTrue(backend.submissions.isEmpty())
    }
}
