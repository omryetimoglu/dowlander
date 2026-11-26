package com.evasi.downloader.queue

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.evasi.downloader.AppSettings
import com.evasi.downloader.Incoming
import com.evasi.downloader.Preferences
import com.evasi.downloader.media.FailureReason
import com.evasi.downloader.media.MediaFailure
import com.evasi.downloader.media.MediaPost
import com.evasi.downloader.media.MediaRepository
import com.evasi.downloader.media.Quality
import com.evasi.downloader.media.TransferMeter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DownloadQueue(
    private val app: Context,
    private val db: QueueDatabase = QueueDatabase.open(app),
    private val settingsFlow: Flow<AppSettings> = Preferences(app).settings,
    private val newWork: () -> OneTimeWorkRequest.Builder = { OneTimeWorkRequestBuilder<DownloadWorker>() },
) {
    private val dao = db.queue()
    private val inputs = QueueInputs(app)
    private val files = HistoryFiles(app)
    private val media = MediaRepository(app)
    private val manager by lazy { WorkManager.getInstance(app) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val historyLock = Mutex()
    private var scheduleRetry: Job? = null
    // Share recent preflight results with the worker; expired URLs are resolved again after a restart or retry.
    private class Preview(val post: MediaPost, val atMs: Long = SystemClock.elapsedRealtime())
    private val previews = android.util.LruCache<String, Preview>(8)
    private val running = mutableMapOf<String, JobLock>()
    private class JobLock(val mutex: Mutex = Mutex(), var users: Int = 0)

    val jobs = dao.jobs()
    val history = dao.history()

    private fun start() {
        scope.launch { guarded("interrupted gallery cleanup") { media.recoverInterrupted() } }
        scope.launch { guarded("schedule") { commands.withLock { schedule(repair = true) } } }
        scope.launch { guarded("input cleanup") { inputs.discardOrphans(dao.neededInputs().toSet()) } }
        scope.launch {
            guarded("gallery scan") { refreshHistory() }
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            files.changes().retryWhen { error, _ ->
                if (error is CancellationException || error !is RuntimeException) return@retryWhen false
                delay(30_000)
                true
            }.debounce(750).collect { guarded("gallery scan") { refreshHistory() } }
        }
    }

    private suspend fun guarded(operation: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // Provider and database exceptions may contain private paths or URLs.
            Log.w("DownloadQueue", "$operation deferred (${error.javaClass.simpleName})")
        }
    }

    suspend fun enqueue(input: Incoming, quality: Quality? = null, post: MediaPost? = null, force: Boolean = false): String =
        add(input, quality, post, force, onlyIndex = null)

    private suspend fun add(input: Incoming, quality: Quality?, post: MediaPost?, force: Boolean, onlyIndex: Int?): String {
        val id = UUID.randomUUID().toString()
        val staged = inputs.prepare(input, id)
        var persisted = false
        try {
            return withContext(Dispatchers.IO) {
                commands.withLock {
                    if (!force) {
                        val duplicate = dao.duplicates(staged.dedupeKey).firstOrNull { old ->
                            old.onlyIndex == null && (old.state != QueueState.COMPLETED ||
                                dao.outputs(old.id).let { it.isNotEmpty() && it.all { row -> files.existing(row.uri) != false } })
                        }
                        if (duplicate != null) {
                            schedule(repair = true)
                            return@withLock duplicate.id
                        }
                    }
                    val settings = settingsFlow.first()
                    withContext(NonCancellable) {
                        dao.insert(QueueEntry(id, staged.dedupeKey, staged.payload, staged.requiresNetwork,
                            dao.nextPosition(), post?.title?.takeIf(String::isNotBlank) ?: staged.previewLabel,
                            staged.platform, staged.sourceUrl, width = quality?.width, height = quality?.height,
                            album = settings.album, previewUrl = post?.previewUrl, description = post?.description,
                            totalBytes = post?.estimatedBytes(quality), itemCount = post?.items?.size ?: 0, onlyIndex = onlyIndex))
                        persisted = true
                        if (input is Incoming.Link && post != null) previews.put(id, Preview(post))
                        schedule(repair = true)
                    }
                    id
                }
            }
        } finally {
            if (!persisted) withContext(NonCancellable + Dispatchers.IO) { inputs.discard(id) }
        }
    }

    suspend fun pause(id: String) = change(id, QueueState.PAUSED, setOf(QueueState.QUEUED, QueueState.DOWNLOADING))
    suspend fun resume(id: String) = change(id, QueueState.QUEUED, setOf(QueueState.PAUSED))
    suspend fun retry(id: String) = change(id, QueueState.QUEUED, setOf(QueueState.FAILED))
    suspend fun cancel(id: String) = change(id, QueueState.CANCELLED,
        setOf(QueueState.QUEUED, QueueState.DOWNLOADING, QueueState.PAUSED, QueueState.FAILED))

    private suspend fun change(id: String, target: QueueState, allowed: Set<QueueState>) = commands.withLock {
        val old = db.withTransaction {
            val row = dao.job(id)?.takeIf { it.state in allowed } ?: return@withTransaction null
            dao.update(row.copy(state = target, lease = null, speedBytesPerSecond = 0, etaSeconds = null, error = null))
            row
        } ?: return@withLock
        old.lease?.let { manager.cancelWorkById(UUID.fromString(it)).await() }
        if (target == QueueState.CANCELLED) withContext(Dispatchers.IO) { inputs.discard(id) }
        previews.remove(id)
        schedule()
    }

    suspend fun move(id: String, delta: Int) = commands.withLock {
        val order = dao.schedulable().filter { it.state == QueueState.QUEUED }
        val index = order.indexOfFirst { it.id == id }
        if (index < 0 || delta == 0) return@withLock
        val other = order.getOrNull(index + delta.coerceIn(-1, 1)) ?: return@withLock
        val changed = db.withTransaction {
            val a = dao.job(id)?.takeIf { it.state == QueueState.QUEUED } ?: return@withTransaction false
            val b = dao.job(other.id)?.takeIf { it.state == QueueState.QUEUED } ?: return@withTransaction false
            dao.update(a.copy(position = b.position))
            dao.update(b.copy(position = a.position))
            true
        }
        if (changed) {
            revokeQueuedLeases()
            schedule()
        }
    }

    /** Replace old WorkManager constraints and refill the configured number of slots. */
    suspend fun settingsChanged() = commands.withLock {
        val settings = settingsFlow.first()
        var active = 0
        for (row in dao.schedulable()) {
            val lease = row.lease ?: continue
            val info = manager.getWorkInfoByIdFlow(UUID.fromString(lease)).first()
            val oldWifi = info?.tags?.contains(WIFI_TAG) == true
            if (row.state == QueueState.QUEUED || oldWifi != (settings.wifiOnly && row.requiresNetwork) ||
                active >= settings.concurrentDownloads) revoke(row) else active++
        }
        schedule(repair = true)
    }

    private suspend fun revokeQueuedLeases() {
        dao.schedulable().filter { it.state == QueueState.QUEUED && it.lease != null }.forEach { revoke(it) }
    }

    private suspend fun revoke(row: QueueEntry) {
        val lease = row.lease ?: return
        if (mutate(row.id, lease) { it.copy(state = QueueState.QUEUED, lease = null, speedBytesPerSecond = 0, etaSeconds = null) }) {
            manager.cancelWorkById(UUID.fromString(lease)).await()
        }
    }

    // A job is accepted once Room commits. If WorkManager is briefly unavailable, retain its position
    // and retry the handoff while this process is alive. Cold starts also reconcile both databases.
    private suspend fun schedule(repair: Boolean = false) {
        if (trySchedule(repair) || scheduleRetry?.isActive == true) return
        scheduleRetry = scope.launch {
            for (waitMs in listOf(1_000L, 10_000L, 60_000L)) {
                delay(waitMs)
                if (commands.withLock { trySchedule(repair = true) }) return@launch
            }
        }
    }

    private suspend fun trySchedule(repair: Boolean): Boolean = try {
        fillSlots(repair)
        true
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.w("DownloadQueue", "Worker scheduling deferred (${error.javaClass.simpleName})")
        false
    }

    /** Only invoked under commands. Room leases reserve slots while the OS waits for constraints. */
    private suspend fun fillSlots(repair: Boolean) {
        val settings = settingsFlow.first()
        if (repair) {
            for (row in dao.schedulable()) {
                val lease = row.lease ?: continue
                val info = manager.getWorkInfoByIdFlow(UUID.fromString(lease)).first()
                if (info == null || info.state.isFinished) mutate(row.id, lease) {
                    it.copy(state = QueueState.QUEUED, lease = null, speedBytesPerSecond = 0, etaSeconds = null)
                }
            }
        }
        val rows = dao.schedulable()
        val capacity = (settings.concurrentDownloads.coerceIn(1, 3) - rows.count { it.lease != null }).coerceAtLeast(0)
        for (row in rows.filter { it.lease == null }.take(capacity)) {
            val wifiOnly = row.requiresNetwork && settings.wifiOnly
            val constraints = Constraints.Builder()
            if (wifiOnly) constraints.setRequiredNetworkRequest(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), NetworkType.UNMETERED)
            else if (row.requiresNetwork) constraints.setRequiredNetworkType(NetworkType.CONNECTED)
            val builder = newWork()
                .setConstraints(constraints.build()).setInputData(workDataOf(JOB to row.id))
            if (wifiOnly) builder.addTag(WIFI_TAG)
            val request = builder.build()
            val lease = request.id.toString()
            dao.update(row.copy(lease = lease))
            try {
                manager.enqueue(request).await()
            } catch (e: Exception) {
                mutate(row.id, lease) { it.copy(lease = null) }
                throw e
            }
        }
    }

    private suspend fun mutate(id: String, lease: String, update: (QueueEntry) -> QueueEntry): Boolean = db.withTransaction {
        val row = dao.job(id)?.takeIf { it.lease == lease } ?: return@withTransaction false
        dao.update(update(row))
        true
    }

    suspend fun outputUris(id: String): List<String> = dao.watchOutputs(id).first()
    internal fun watchJob(id: String) = dao.watchJob(id)
    fun observeOutputUris(id: String): Flow<List<String>> = dao.watchOutputs(id)

    suspend fun refreshHistory() = withContext(Dispatchers.IO) {
        historyLock.withLock {
            if (!dao.flagged(IMPORT_DONE)) {
                val prior = files.legacy().map { metadata -> metadata.entry(null, "Device", null, 0, null, null) }
                db.withTransaction {
                    dao.importRecords(prior)
                    dao.flag(QueueFlag(IMPORT_DONE))
                }
            }
            val observations = dao.trackedUris().mapNotNull { uri -> files.existing(uri)?.let { uri to it } }
            db.withTransaction {
                for ((uri, exists) in observations) {
                    dao.markMissing(uri, !exists)
                    dao.markAvailable(uri, exists)
                }
            }
        }
    }

    suspend fun clearHistory() = historyLock.withLock {
        db.withTransaction { dao.clearHistory(); dao.flag(QueueFlag(IMPORT_DONE)) }
    }

    suspend fun deleteHistory(uri: String): Boolean = withContext(Dispatchers.IO) {
        historyLock.withLock {
            if (dao.file(uri) == null) return@withLock false
            if (files.existing(uri) != false && !files.delete(uri)) return@withLock false
            db.withTransaction { dao.removeFile(uri); dao.markAvailable(uri, false) }
            true
        }
    }

    suspend fun redownload(uri: String): String? {
        val entry = dao.file(uri) ?: return null
        return if (entry.sourceUrl != null) add(Incoming.Link(entry.sourceUrl), null, null, true, entry.itemIndex)
        else if (!entry.missing) enqueue(Incoming.Files(listOf(entry.uri), entry.mime), force = true)
        else null
    }

    companion object {
        internal const val JOB = "queueJob"
        private const val WIFI_TAG = "wifi-only"
        private const val IMPORT_DONE = "imported_gallery"
        @Volatile private var instance: DownloadQueue? = null
        fun get(context: Context): DownloadQueue = instance ?: synchronized(this) {
            instance ?: DownloadQueue(context.applicationContext).also { instance = it; it.start() }
        }
    }

    /** A canceled worker and its resumed replacement must not write the same album item concurrently. */
    internal suspend fun perform(id: String, lease: String, foreground: suspend (QueueEntry) -> Unit) {
        val guard = synchronized(running) { running.getOrPut(id) { JobLock() }.also { it.users++ } }
        try {
            guard.mutex.withLock {
                val claimed = commands.withLock {
                    mutate(id, lease) { it.copy(state = QueueState.DOWNLOADING, error = null) }
                }
                if (!claimed) return
                var finished = false
                try {
                    foreground(requireNotNull(dao.job(id)))
                    transfer(id, lease)
                    finished = mutate(id, lease) { it.copy(state = QueueState.COMPLETED, lease = null,
                        speedBytesPerSecond = 0, etaSeconds = null, totalBytes = it.downloadedBytes) }
                    if (finished) inputs.discard(id)
                } catch (stop: CancellationException) {
                    // WorkManager can restart this same work ID when network constraints return.
                    withContext(NonCancellable) {
                        mutate(id, lease) { it.copy(state = QueueState.QUEUED, speedBytesPerSecond = 0, etaSeconds = null) }
                    }
                    throw stop
                } catch (failure: Exception) {
                    val reason = (failure as? MediaFailure)?.reason?.name ?: when (failure) {
                        is SecurityException -> FailureReason.PERMISSION.name
                        is java.io.IOException -> FailureReason.STORAGE.name
                        else -> "UNKNOWN"
                    }
                    finished = mutate(id, lease) { it.copy(state = QueueState.FAILED, lease = null,
                        speedBytesPerSecond = 0, etaSeconds = null, error = reason) }
                } finally {
                    if (finished) withContext(NonCancellable) { commands.withLock { schedule() } }
                }
            }
        } finally {
            synchronized(running) { if (--guard.users == 0) running.remove(id) }
        }
    }

    private suspend fun transfer(id: String, lease: String) {
        val job = dao.job(id) ?: return
        var saved = dao.outputs(id)
        for (checkpoint in saved) media.forgetOutput("$id-${checkpoint.itemIndex}")
        // Reconcile publication that preceded process death, even when the original URL has since expired.
        for (index in saved.size until job.itemCount) {
            val key = "$id-$index"
            val previouslyPublished = media.publishedOutput(key) ?: break
            record(job, index, previouslyPublished.toString(), job.title, null)
            media.forgetOutput(key)
        }
        saved = dao.outputs(id)
        if (saved.isNotEmpty() && saved.size == job.itemCount) return

        val post = previews.remove(id)?.takeIf { SystemClock.elapsedRealtime() - it.atMs < 5 * 60_000 }?.post ?: when (val input = inputs.restore(job.payload)) {
            is Incoming.Link -> media.resolve(input.url)
            is Incoming.Files -> {
                val posts = input.uris.map { media.resolveShared(Uri.parse(it), input.mime) }
                posts.singleOrNull() ?: MediaPost(posts.flatMap { it.items })
            }
            Incoming.Invalid -> throw MediaFailure(FailureReason.INVALID)
        }
        val items = if (job.onlyIndex == null) post.items else listOfNotNull(post.items.getOrNull(job.onlyIndex))
        if (items.isEmpty() || saved.size > items.size || (saved.isNotEmpty() && items.size != job.itemCount)) {
            throw MediaFailure(FailureReason.UNAVAILABLE)
        }
        val quality = if (job.width != null && job.height != null) Quality(job.width, job.height) else null
        var completedBytes = saved.sumOf { it.size.coerceAtLeast(0) }
        val estimatedTotal = MediaPost(items).estimatedBytes(quality)
        if (!mutate(id, lease) { it.copy(title = post.title.ifBlank { it.title }, description = post.description ?: it.description,
            previewUrl = post.previewUrl ?: it.previewUrl, itemCount = items.size, downloadedBytes = completedBytes,
            totalBytes = estimatedTotal) }) return

        for (index in saved.size until items.size) {
            currentCoroutineContext().ensureActive()
            if (!mutate(id, lease) { it.copy(itemIndex = index, speedBytesPerSecond = 0, etaSeconds = null) }) return
            val item = items[index]
            val key = "$id-$index"
            val uri = coroutineScope {
                val updates = Channel<Pair<Long, Long?>>(Channel.CONFLATED)
                val reporting = launch {
                    val meter = TransferMeter()
                    var highestBytes = 0L
                    var lastReported = -1000L
                    for ((bytes, total) in updates) {
                        highestBytes = maxOf(highestBytes, bytes)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReported < 500) continue
                        lastReported = now
                        val estimate = if (total != null && index == items.lastIndex) completedBytes + total else estimatedTotal
                        val count = completedBytes + highestBytes
                        val validEstimate = estimate?.takeIf { it >= count }
                        val stats = meter.sample(count, validEstimate, now)
                        mutate(id, lease) { it.copy(downloadedBytes = count, totalBytes = validEstimate,
                            speedBytesPerSecond = stats.speedBytesPerSecond ?: 0L, etaSeconds = stats.etaSeconds) }
                    }
                }
                try {
                    media.save(item, quality, job.album, key) { bytes, total -> updates.trySend(bytes to total) }.toString()
                } finally {
                    updates.close()
                    reporting.join()
                }
            }
            // A stable publication receipt bridges the two independent stores (MediaStore and Room).
            withContext(NonCancellable) {
                val choice = item.sources.firstOrNull { quality == null || it.quality == quality }
                completedBytes += record(job, index, uri, choice?.name ?: job.title, quality ?: choice?.quality)
                media.forgetOutput(key)
            }
        }
    }

    private suspend fun record(job: QueueEntry, index: Int, uri: String, name: String, quality: Quality?): Long {
        val metadata = files.describe(uri, name, quality?.width, quality?.height)
        db.withTransaction {
            val current = dao.job(job.id) ?: return@withTransaction
            dao.checkpoint(Checkpoint(job.id, index, uri, metadata.size))
            dao.record(metadata.entry(job.id, current.platform, current.sourceUrl, job.onlyIndex ?: index,
                current.description, current.previewUrl))
            val outputs = dao.outputs(job.id)
            dao.update(current.copy(savedCount = outputs.size, lastUri = outputs.last().uri,
                downloadedBytes = outputs.sumOf { it.size.coerceAtLeast(0) }))
        }
        return metadata.size.coerceAtLeast(0)
    }

    private fun FileMetadata.entry(jobId: String?, platform: String, url: String?, index: Int, text: String?, preview: String?) =
        HistoryEntry(uri, jobId, name, mime, date, size, width, height, platform, url, index, description = text, previewUrl = preview)
}
