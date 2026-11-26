package com.evasi.downloader

import com.evasi.downloader.media.*
import com.evasi.downloader.ui.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSessionTest {
    private val a = Incoming.Link("https://mastodon.social/@sample/123")
    private val b = Incoming.Link("https://mastodon.social/@sample/456")

    private fun samplePost(count: Int = 1): MediaPost = MediaPost((1..count).map { index ->
        MediaItem(MediaKind.VIDEO, listOf(
            MediaSource("https://example.org/$index.mp4", "video/mp4", Quality(1920, 1080)),
            MediaSource("https://example.org/$index-small.mp4", "video/mp4", Quality(640, 360))
        ))
    })

    private class Backend(private val result: MediaPost) : TransferBackend {
        var latency = 0L
        var transferLatency = 0L
        var failOnceAt = -1
        val resolved = mutableListOf<Incoming>()
        val writes = mutableListOf<Pair<MediaItem, Quality?>>()
        override suspend fun resolve(input: Incoming): MediaPost {
            delay(latency)
            resolved += input
            return result
        }
        override suspend fun save(item: MediaItem, quality: Quality?, progress: (Long, Long?) -> Unit): String {
            delay(transferLatency)
            if (writes.size == failOnceAt) {
                failOnceAt = -1
                throw MediaFailure(FailureReason.NETWORK)
            }
            writes += item to quality
            progress(42, 42)
            return "content://media/external/video/media/" + writes.size
        }
    }

    private fun TestScope.session(backend: Backend, autoStart: Boolean = true) =
        DownloadSession(this, backend, "en", autoStart) { testScheduler.currentTime }

    private fun DownloadSession.placed() = event(UiEvent.ControlsReady(state.value.controlsGeneration))

    @Test fun deadlineStartsOnlyWhenBothControlsArePlacedAndSurvivesRotation() = runTest {
        val backend = Backend(samplePost())
        val session = session(backend)
        session.accept(a, Source.Clipboard)
        advanceTimeBy(5000)
        assertTrue(backend.writes.isEmpty())
        session.placed()
        advanceTimeBy(750)
        session.event(UiEvent.ControlsHidden(session.state.value.controlsGeneration))
        advanceTimeBy(5000)
        session.placed()
        advanceTimeBy(749)
        runCurrent()
        assertTrue(backend.writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        session.event(UiEvent.Start)
        session.placed()
        advanceUntilIdle()
        assertEquals(1, backend.writes.size)
        assertNull(backend.writes.single().second)
        assertEquals(Phase.Saved, session.state.value.phase)
    }

    @Test fun qualityPointerDownBeforeDeadlineDisarmsWhileResolverIsPending() = runTest {
        val backend = Backend(samplePost()).apply { latency = 3000 }
        val session = session(backend)
        session.accept(a, Source.Clipboard)
        session.placed()
        advanceTimeBy(1499)
        session.event(UiEvent.QualityTouched)
        advanceTimeBy(200)
        session.event(UiEvent.OpenQuality)
        assertTrue(session.state.value.qualitySheet)
        assertTrue(session.state.value.resolving)
        session.event(UiEvent.CloseQuality)
        session.placed()
        advanceUntilIdle()
        assertTrue(backend.writes.isEmpty())
        session.event(UiEvent.OpenQuality)
        session.event(UiEvent.SelectQuality(session.state.value.qualityOptions.last()))
        assertNotNull(session.state.value.quality)
        session.placed()
        advanceTimeBy(5000)
        assertTrue(backend.writes.isEmpty())
        repeat(2) { session.event(UiEvent.Start) }
        advanceUntilIdle()
        assertEquals(samplePost().qualities.last(), backend.writes.single().second)
    }

    @Test fun unresolvedAutoStartCommitsOnceAsSoonAsTheSourceArrives() = runTest {
        val backend = Backend(samplePost()).apply { latency = 8000 }
        val session = session(backend)
        session.accept(a, Source.Clipboard)
        session.placed()
        advanceTimeBy(1500)
        session.event(UiEvent.Start)
        runCurrent()
        assertEquals(Phase.Downloading, session.state.value.phase)
        assertTrue(session.state.value.resolving)
        advanceUntilIdle()
        assertEquals(1, backend.writes.size)
    }

    @Test fun incomingShareSupersedesClipboardAndIgnoresTheAutoStartSetting() = runTest {
        val backend = Backend(samplePost()).apply { latency = 5000 }
        val session = session(backend, autoStart = false)
        session.accept(a, Source.Clipboard)
        session.placed()
        advanceTimeBy(2000)
        assertTrue(backend.writes.isEmpty())
        session.accept(b, Source.Share)
        session.accept(b, Source.Share)
        assertNull(session.state.value.countdown)
        assertEquals(Phase.Downloading, session.state.value.phase)
        advanceUntilIdle()
        assertEquals(listOf(b), backend.resolved)
        assertEquals(1, backend.writes.size)
        assertNull(backend.writes.single().second)
    }

    @Test fun cancelStopsTransferAndRetryContinuesAfterAlreadyPublishedItems() = runTest {
        val backend = Backend(samplePost(count = 2)).apply { transferLatency = 1000; failOnceAt = 1 }
        val session = session(backend)
        session.accept(b, Source.Share)
        advanceTimeBy(100)
        session.event(UiEvent.Cancel)
        advanceUntilIdle()
        assertTrue(backend.writes.isEmpty())
        assertEquals(Phase.Cancelled, session.state.value.phase)
        session.event(UiEvent.Retry)
        advanceUntilIdle()
        assertEquals(1, session.state.value.savedCount)
        assertEquals(Phase.Failed, session.state.value.phase)
        session.event(UiEvent.Retry)
        advanceUntilIdle()
        assertEquals(Phase.Saved, session.state.value.phase)
        assertEquals(2, backend.writes.size)
        assertEquals(2, backend.writes.map { it.first }.distinct().size)
    }
}
