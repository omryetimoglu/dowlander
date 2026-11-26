package com.evasi.downloader

import com.evasi.downloader.media.FailureReason
import com.evasi.downloader.media.MediaFailure
import com.evasi.downloader.media.MediaItem
import com.evasi.downloader.media.MediaPost
import com.evasi.downloader.media.Quality
import com.evasi.downloader.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface TransferBackend {
    suspend fun resolve(input: Incoming): MediaPost
    suspend fun save(item: MediaItem, quality: Quality?, progress: (Long, Long?) -> Unit): String
}

/** Entry points run on the main dispatcher; background byte counts are routed back to it. */
internal class DownloadSession(
    private val scope: CoroutineScope,
    private val backend: TransferBackend,
    initialLanguage: String,
    autoStart: Boolean,
    private val elapsedMs: () -> Long
) {
    private val mutable = MutableStateFlow(ScreenState(language = initialLanguage, autoStart = autoStart))
    val state = mutable.asStateFlow()
    private var screen: ScreenState
        get() = mutable.value
        set(value) { mutable.value = value }

    private var input: Incoming = Incoming.Invalid
    private var post: MediaPost? = null
    private var selected: Quality? = null
    private val saved = mutableListOf<String>()
    val galleryUri get() = saved.lastOrNull()
    private var generation = 0L
    private var committed = false
    private var disarmed = false
    private var controlsVisible = false
    private var remainingMs = 1500L
    private var deadline = 0L
    private var timer: Job? = null
    private var resolver: Job? = null
    private var transfer: Job? = null

    fun accept(value: Incoming, source: Source) {
        if (source == Source.Share && input == value && screen.source == source &&
            screen.phase == Phase.Downloading) return
        generation++
        resetJobs()
        val current = screen
        input = value
        post = null
        saved.clear()
        selected = null
        committed = source == Source.Share
        disarmed = committed
        remainingMs = 1500L
        controlsVisible = false
        screen = ScreenState(
            source = source, phase = if (committed) Phase.Downloading else Phase.Preparing,
            link = (value as? Incoming.Link)?.url.orEmpty(), controlsGeneration = generation,
            language = current.language, autoStart = current.autoStart, resolving = true
        )
        resolve()
    }

    fun event(event: UiEvent) {
        when (event) {
            is UiEvent.ControlsReady -> if (generation == event.generation) {
                controlsVisible = true
                maybeStartTimer()
            }
            is UiEvent.ControlsHidden -> if (generation == event.generation) {
                controlsVisible = false
                pauseTimer()
            }
            UiEvent.QualityTouched -> if (waiting()) disarm()
            UiEvent.OpenQuality -> if (waiting()) {
                disarm()
                screen = screen.copy(qualitySheet = true)
            }
            UiEvent.CloseQuality -> screen = screen.copy(qualitySheet = false)
            is UiEvent.SelectQuality -> if (waiting()) {
                val option = post?.qualities?.firstOrNull { label(it) == event.value } ?: return
                disarm()
                selected = option
                screen = screen.copy(quality = label(option), qualitySheet = false)
            }
            UiEvent.Start -> if (waiting()) commit()
            UiEvent.Cancel -> if (screen.phase in listOf(Phase.Downloading, Phase.Preparing, Phase.Ready)) {
                generation++
                resetJobs()
                disarmed = true
                screen = screen.copy(phase = Phase.Cancelled, resolving = false, qualitySheet = false,
                    countdown = null, controlsGeneration = generation, progress = null)
            }
            UiEvent.Retry -> if (screen.phase == Phase.Failed || screen.phase == Phase.Cancelled) {
                if (input == Incoming.Invalid) return
                if (post != null && committed) download()
                else {
                    screen = screen.copy(phase = if (committed) Phase.Downloading else Phase.Preparing,
                        error = null, resolving = true)
                    if (post == null) resolve() else ready()
                }
            }
            is UiEvent.SetAutoStart -> {
                screen = screen.copy(autoStart = event.enabled)
                if (event.enabled) maybeStartTimer() else pauseTimer()
            }
            is UiEvent.SetLanguage -> screen = screen.copy(language = event.tag)
            else -> Unit
        }
    }

    private fun waiting() = !committed && screen.source == Source.Clipboard &&
        (screen.phase == Phase.Preparing || screen.phase == Phase.Ready)

    private fun resolve() {
        val token = generation
        if (input == Incoming.Invalid) {
            failed(UiError.InvalidLink)
            return
        }
        resolver = scope.launch {
            try {
                val resolved = backend.resolve(input)
                if (token != generation) return@launch
                if (resolved.items.isEmpty()) { failed(UiError.NoMedia); return@launch }
                post = resolved
                screen = screen.copy(title = resolved.title, qualityOptions = resolved.qualities.map(::label),
                    total = resolved.items.size, resolving = false)
                if (committed) download() else ready()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == generation) failed(error.toUiError()) }
        }
    }

    private fun ready() {
        screen = screen.copy(phase = Phase.Ready, resolving = false)
        maybeStartTimer()
    }

    private fun commit() {
        if (committed) return
        committed = true
        disarm()
        screen = screen.copy(phase = Phase.Downloading, qualitySheet = false)
        if (post != null) download()
    }

    private fun download() {
        val items = post?.items ?: return
        val token = generation
        val quality = selected
        screen = screen.copy(phase = Phase.Downloading, error = null, resolving = false, progress = null)
        transfer = scope.launch {
            try {
                for (index in saved.size until items.size) {
                    screen = screen.copy(item = index + 1, progress = null)
                    val uri = backend.save(items[index], quality) { bytes, length ->
                        if (length != null && length > 0) scope.launch {
                            if (token == generation && screen.phase == Phase.Downloading && screen.item == index + 1) {
                                screen = screen.copy(progress = (bytes.toDouble() / length).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    if (token != generation) return@launch
                    saved += uri
                    screen = screen.copy(savedCount = saved.size)
                }
                screen = screen.copy(phase = Phase.Saved, progress = 1f)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == generation) failed(error.toUiError()) }
        }
    }

    private fun failed(error: UiError) {
        pauseTimer()
        screen = screen.copy(phase = Phase.Failed, error = error, countdown = null,
            resolving = false, progress = null, qualitySheet = false)
    }

    private fun maybeStartTimer() {
        if (!waiting() || disarmed || !screen.autoStart || !controlsVisible || timer?.isActive == true) return
        deadline = elapsedMs() + remainingMs
        timer = scope.launch {
            while (waiting() && controlsVisible && !disarmed) {
                remainingMs = (deadline - elapsedMs()).coerceAtLeast(0)
                if (remainingMs == 0L) { commit(); return@launch }
                screen = screen.copy(countdown = remainingMs / 1500f)
                delay(minOf(remainingMs, 25L))
            }
        }
    }

    private fun pauseTimer() {
        if (timer != null) remainingMs = (deadline - elapsedMs()).coerceIn(0L, 1500L)
        timer?.cancel()
        timer = null
        if (screen.countdown != null) screen = screen.copy(countdown = null)
    }

    private fun disarm() { disarmed = true; pauseTimer() }
    private fun resetJobs() { pauseTimer(); resolver?.cancel(); transfer?.cancel() }
    private fun label(q: Quality) = if (q.width > 0 && q.height > 0) "${q.width} × ${q.height}" else "${q.height}p"

    private fun Exception.toUiError() = when ((this as? MediaFailure)?.reason) {
        FailureReason.NETWORK -> UiError.Network
        FailureReason.STORAGE -> UiError.Write
        FailureReason.UNAVAILABLE -> UiError.Unavailable
        FailureReason.UNSUPPORTED -> UiError.Unsupported
        FailureReason.INVALID -> UiError.Read
        else -> UiError.Unknown
    }
}
