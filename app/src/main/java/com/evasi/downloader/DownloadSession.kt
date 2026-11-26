package com.evasi.downloader

import com.evasi.downloader.media.*
import com.evasi.downloader.queue.QueueEntry
import com.evasi.downloader.queue.QueueState
import com.evasi.downloader.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SavedOutput(val uris: List<String>, val fileName: String? = null,
    val quality: Quality? = null, val mime: String? = null)

internal interface HomeBackend {
    suspend fun resolve(input: Incoming.Link): MediaPost
    suspend fun enqueue(input: Incoming, quality: Quality?, post: MediaPost?): String
    fun observe(id: String): Flow<QueueEntry>
    suspend fun outputs(id: String): SavedOutput
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
}

/** Owns the editable Home draft. Once submitted, a transfer belongs to the durable queue. */
internal class DownloadSession(
    private val scope: CoroutineScope,
    private val backend: HomeBackend,
    initialLanguage: String,
    autoStart: Boolean,
    private val elapsedMs: () -> Long,
    private val rememberJob: (String?) -> Unit = {},
) {
    private val mutable = MutableStateFlow(ScreenState(language = initialLanguage, autoStart = autoStart))
    val state = mutable.asStateFlow()
    private var screen: ScreenState
        get() = mutable.value
        set(value) { mutable.value = value }

    private var input: Incoming = Incoming.Invalid
    private var post: MediaPost? = null
    private var options = emptyMap<String, Quality>()
    private var preview: Job? = null
    private var watching: Job? = null
    private var draftId = 0L
    private var jobId: String? = null
    private var disarmed = false
    private class Submission(var cancelled: Boolean = false)
    private var submitting: Submission? = null
    var preferredSize: Int = 0
    val galleryUri get() = screen.savedUris.lastOrNull()

    fun accept(value: Incoming, source: Source) {
        if (source == Source.Share && input == value && screen.phase in setOf(Phase.Queued, Phase.Downloading)) return
        resetDraft(source, (value as? Incoming.Link)?.url.orEmpty())
        input = value
        if (value == Incoming.Invalid) return fail(UiError.InvalidLink)
        if (source == Source.Share) submit()
        else if (value is Incoming.Link) prepare(value, debounce = false)
        else fail(UiError.InvalidLink)
    }

    fun restore(id: String) {
        resetDraft(Source.None, "")
        watch(id)
    }

    private fun resetDraft(source: Source, text: String) {
        draftId++
        preview?.cancel()
        watching?.cancel()
        jobId = null
        rememberJob(null)
        input = Incoming.Invalid
        post = null
        options = emptyMap()
        disarmed = false
        submitting = null
        screen = ScreenState(source = source, link = text, controlsGeneration = draftId, language = screen.language, autoStart = screen.autoStart)
    }

    private fun edit(text: String) {
        if (text == screen.link) return
        resetDraft(Source.Manual, text)
        if (text.isBlank()) return
        val link = SharedLinks.extract(text)
        if (link == null) {
            preview = scope.launch { delay(800); fail(UiError.InvalidLink) }
            return
        }
        val value = Incoming.Link(link)
        input = value
        prepare(value, debounce = true)
    }

    private fun prepare(value: Incoming.Link, debounce: Boolean) {
        val token = draftId
        val began = elapsedMs()
        screen = screen.copy(phase = Phase.Preparing, resolving = true)
        preview = scope.launch {
            try {
                if (debounce) delay(350)
                val info = backend.resolve(value)
                if (token != draftId) return@launch
                if (info.items.isEmpty()) return@launch fail(UiError.NoMedia)
                val remaining = 1500 - (elapsedMs() - began)
                if (remaining > 0) delay(remaining)
                if (token != draftId) return@launch
                post = info
                val sizes = info.qualities
                options = sizes.associateBy { q ->
                    val p = q.pixels()
                    when {
                        sizes.count { it.pixels() == p } > 1 -> "${p}p (${q.width} × ${q.height})"
                        p == 2160 -> "2160p (4K)"
                        else -> "${p}p"
                    }
                }
                val automatic = screen.autoStart && !disarmed
                val preferred = if (automatic || preferredSize <= 0) null else
                    options.entries.firstOrNull { it.value.pixels() <= preferredSize } ?: options.entries.lastOrNull()
                val first = info.items.first()
                screen = screen.copy(phase = Phase.Ready, resolving = false, title = info.title,
                    fileName = first.sources.firstOrNull()?.name.orEmpty(), mediaKind = first.kind,
                    previewUri = info.previewUrl, total = info.items.size, qualityOptions = options.keys.toList(),
                    qualitySizes = options.mapNotNull { (label, q) -> info.estimatedBytes(q)?.let { label to it } }.toMap(),
                    totalBytes = info.estimatedBytes(preferred?.value), quality = preferred?.key,
                    description = info.description, resolution = dimensions(preferred?.value ?: first.sources.firstOrNull()?.quality))
                if (automatic) submit()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == draftId) fail(error.uiError()) }
        }
    }

    private fun submit() {
        val token = draftId
        val value = input
        val info = post
        val quality = options[screen.quality]
        screen = screen.copy(phase = Phase.Queued, resolving = false, qualitySheet = false, error = null)
        val submission = Submission().also { submitting = it }
        // Starting another draft must not drop an explicit Share command while its files are being staged.
        scope.launch {
            try {
                val id = backend.enqueue(value, quality, info)
                if (submission.cancelled) backend.cancel(id)
                if (token != draftId) return@launch
                submitting = null
                watch(id)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == draftId) fail(error.uiError()) }
        }
    }

    private fun watch(id: String) {
        val token = draftId
        jobId = id
        rememberJob(id)
        watching?.cancel()
        watching = scope.launch {
            try {
                backend.observe(id).collect { row ->
                    if (token != draftId) return@collect
                    val done = row.state == QueueState.COMPLETED
                    val saved = if (done) backend.outputs(id) else null
                    val outputs = saved?.uris ?: listOfNotNull(row.lastUri)
                    if (token != draftId) return@collect
                    screen = screen.copy(
                        phase = when (row.state) {
                            QueueState.QUEUED -> Phase.Queued
                            QueueState.DOWNLOADING -> Phase.Downloading
                            QueueState.COMPLETED -> Phase.Saved
                            QueueState.FAILED -> Phase.Failed
                            QueueState.PAUSED -> Phase.Paused
                            QueueState.CANCELLED -> Phase.Cancelled
                        },
                        title = row.title, fileName = saved?.fileName ?: screen.fileName.ifBlank { row.title },
                        resolution = dimensions(saved?.quality) ?: screen.resolution,
                        mediaKind = when {
                            saved?.mime == "image/gif" -> MediaKind.GIF
                            saved?.mime?.startsWith("video/") == true -> MediaKind.VIDEO
                            saved?.mime?.startsWith("image/") == true -> MediaKind.PHOTO
                            else -> screen.mediaKind
                        }, link = screen.link.ifBlank { row.sourceUrl.orEmpty() },
                        previewUri = outputs.lastOrNull() ?: row.previewUrl ?: screen.previewUri,
                        total = row.itemCount, item = row.itemIndex + 1, savedCount = row.savedCount,
                        downloadedBytes = row.downloadedBytes, totalBytes = row.totalBytes,
                        progress = if (done) 1f else row.totalBytes?.takeIf { it > 0 }?.let { (row.downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) },
                        bytesPerSecond = row.speedBytesPerSecond.takeIf { it > 0 }, remainingSeconds = row.etaSeconds,
                        savedUris = outputs, description = row.description ?: screen.description,
                        error = if (row.state == QueueState.FAILED) FailureReason.entries.firstOrNull { it.name == row.error }.uiError() else null,
                    )
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == draftId) fail(error.uiError()) }
        }
    }

    fun event(event: UiEvent) {
        when (event) {
            is UiEvent.EditLink -> edit(event.text)
            UiEvent.Start -> if (screen.phase == Phase.Ready) submit()
            UiEvent.OpenQuality -> if (screen.phase == Phase.Ready || screen.phase == Phase.Preparing) {
                disarmed = true
                screen = screen.copy(qualitySheet = true)
            }
            UiEvent.QualityTouched -> disarmed = true
            UiEvent.CloseQuality -> screen = screen.copy(qualitySheet = false)
            is UiEvent.SelectQuality -> options[event.value]?.let { select(event.value, it) }
            UiEvent.SelectOriginal -> select(null, null)
            is UiEvent.SetAutoStart -> screen = screen.copy(autoStart = event.enabled)
            is UiEvent.SetLanguage -> screen = screen.copy(language = event.tag)
            UiEvent.Cancel -> {
                val id = jobId
                if (id != null) command { backend.cancel(id) }
                else if (screen.phase == Phase.Queued) submitting?.cancelled = true
                else { draftId++; preview?.cancel(); screen = screen.copy(phase = Phase.Cancelled, resolving = false, qualitySheet = false) }
            }
            UiEvent.Retry -> {
                val id = jobId
                if (id != null && screen.phase == Phase.Failed) command { backend.retry(id) }
                else if (screen.phase == Phase.Cancelled && input == Incoming.Invalid && screen.link.isNotBlank())
                    accept(Incoming.Link(screen.link), Source.Manual)
                else if (input != Incoming.Invalid) accept(input, screen.source)
            }
            else -> Unit
        }
    }

    private fun command(action: suspend () -> Unit) {
        val token = draftId
        scope.launch {
            try { action() } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == draftId) fail(error.uiError()) }
        }
    }

    private fun select(label: String?, quality: Quality?) {
        if (screen.phase != Phase.Ready) return
        disarmed = true
        screen = screen.copy(quality = label, qualitySheet = false, totalBytes = post?.estimatedBytes(quality),
            resolution = dimensions(quality ?: post?.items?.firstOrNull()?.sources?.firstOrNull()?.quality))
    }

    private fun fail(error: UiError) { screen = screen.copy(phase = Phase.Failed, error = error, qualitySheet = false, resolving = false) }
    private fun dimensions(q: Quality?) = q?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" }
    private fun Quality.pixels() = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)
    private fun Exception.uiError() = (this as? MediaFailure)?.reason.uiError()
    private fun FailureReason?.uiError() = when (this?.name) {
        "NETWORK" -> UiError.Network; "STORAGE" -> UiError.Write; "UNAVAILABLE" -> UiError.Unavailable
        "UNSUPPORTED" -> UiError.Unsupported; "INVALID" -> UiError.Read; "DELETED" -> UiError.Deleted
        "PRIVATE" -> UiError.Private; "TIMEOUT" -> UiError.Timeout; "NO_SPACE" -> UiError.NoSpace
        "PERMISSION" -> UiError.Permission; else -> UiError.Unknown
    }
}
