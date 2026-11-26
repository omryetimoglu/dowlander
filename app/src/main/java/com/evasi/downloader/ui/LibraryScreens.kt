package com.evasi.downloader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.evasi.downloader.R
import com.evasi.downloader.queue.DownloadQueue
import com.evasi.downloader.queue.HistoryEntry
import com.evasi.downloader.queue.QueueState
import com.evasi.downloader.share.mediaShareChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun QueueScreen(queue: DownloadQueue) {
    val jobs by queue.jobs.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val waiting = jobs.filter { it.state == QueueState.QUEUED }.map { it.id }
    val host = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun change(action: suspend () -> Unit) {
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { host.showSnackbar(context.getString(R.string.library_action_failed)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (jobs.isEmpty()) item { EmptyLibrary(R.string.queue_empty, R.string.queue_intro) }
            items(jobs, key = { it.id }) { job ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (job.previewUrl != null) {
                                MediaThumbnail(job.previewUrl, Modifier.size(50.dp)) {
                                    PlatformBadge(job.platform, Modifier.size(28.dp))
                                }
                            }
                            PlatformBadge(job.platform.ifBlank { job.sourceUrl.orEmpty() }, Modifier.size(26.dp))
                            Column {
                                Text(job.title.ifBlank { job.sourceUrl ?: context.getString(R.string.share_label) },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(statusText(job.state)), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (job.state == QueueState.DOWNLOADING) {
                            val total = job.totalBytes
                            if (total != null && total > 0) {
                                LinearProgressIndicator(progress = { (job.downloadedBytes.toDouble()/total).toFloat().coerceIn(0f,1f) },
                                    modifier = Modifier.fillMaxWidth())
                            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            val received = Formatter.formatShortFileSize(context, job.downloadedBytes)
                            Text(if (total != null && total > 0) "$received / ${Formatter.formatShortFileSize(context,total)}"
                                else stringResource(R.string.queue_unknown_total, received), style = MaterialTheme.typography.bodyMedium)
                            val transferInfo = listOfNotNull(
                                total?.takeIf { it > 0 }?.let { stringResource(R.string.transfer_percent,
                                    (job.downloadedBytes * 100.0 / it).coerceIn(0.0, 100.0)) },
                                job.speedBytesPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.transfer_speed,
                                    Formatter.formatShortFileSize(context, it)) },
                                job.etaSeconds?.let { stringResource(R.string.transfer_eta,
                                    android.text.format.DateUtils.formatElapsedTime(it.coerceAtLeast(0))) },
                            )
                            if (transferInfo.isNotEmpty()) Text(transferInfo.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                        if (job.state == QueueState.FAILED) Text(stringResource(queueError(job.error)),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (job.state in listOf(QueueState.QUEUED, QueueState.DOWNLOADING, QueueState.PAUSED, QueueState.FAILED)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                when (job.state) {
                                    QueueState.QUEUED, QueueState.DOWNLOADING ->
                                        TextButton(onClick = { change { queue.pause(job.id) } }) { Text(stringResource(R.string.queue_pause)) }
                                    QueueState.PAUSED -> TextButton(onClick = { change { queue.resume(job.id) } }) { Text(stringResource(R.string.queue_resume)) }
                                    QueueState.FAILED -> TextButton(onClick = { change { queue.retry(job.id) } }) { Text(stringResource(R.string.retry)) }
                                    else -> Unit
                                }
                                TextButton(onClick = { change { queue.cancel(job.id) } }) { Text(stringResource(R.string.cancel)) }
                                if (job.state == QueueState.QUEUED && waiting.size > 1) {
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { menu = true }) { Text(stringResource(R.string.queue_move)) }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_earlier)) },
                                                enabled = job.id != waiting.first(), onClick = { menu = false; change { queue.move(job.id, -1) } })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_later)) },
                                                enabled = job.id != waiting.last(), onClick = { menu = false; change { queue.move(job.id, 1) } })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
    }
}

@StringRes
private fun statusText(state: QueueState) = when (state) {
    QueueState.QUEUED -> R.string.library_waiting
    QueueState.DOWNLOADING -> R.string.queue_active
    QueueState.PAUSED -> R.string.queue_paused
    QueueState.FAILED -> R.string.failed_title
    QueueState.COMPLETED -> R.string.queue_complete
    QueueState.CANCELLED -> R.string.cancelled_title
}

@StringRes
private fun queueError(reason: String?) = when (reason) {
    "NETWORK" -> R.string.error_network
    "UNAVAILABLE" -> R.string.error_unavailable
    "PRIVATE" -> R.string.home_private
    "DELETED" -> R.string.home_deleted
    "TIMEOUT" -> R.string.home_timeout
    "PERMISSION" -> R.string.home_permission
    "NO_SPACE" -> R.string.home_no_space
    "UNSUPPORTED" -> R.string.error_unsupported
    "STORAGE" -> R.string.error_write
    "INVALID" -> R.string.error_invalid_link
    else -> R.string.error_unknown
}

@Composable
internal fun HistoryScreen(queue: DownloadQueue) {
    val entries by queue.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(queue, owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try { queue.refreshHistory() } catch (e: CancellationException) { throw e }
        catch (_: RuntimeException) { /* Keep available Room records while the gallery is unavailable. */ }
    } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { SnackbarHostState() }
    var details by remember { mutableStateOf<HistoryEntry?>(null) }
    var deletion by remember { mutableStateOf<HistoryEntry?>(null) }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (_: ActivityNotFoundException) { host.showSnackbar(context.getString(R.string.gallery_unavailable)) }
            catch (_: Exception) { host.showSnackbar(context.getString(R.string.library_action_failed)) }
        }
    }
    val locale = context.resources.configuration.locales[0]
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    @Composable fun facts(entry: HistoryEntry) = listOfNotNull(
        Formatter.formatShortFileSize(context, entry.size),
        if (entry.width != null && entry.height != null) "${entry.width} × ${entry.height}" else null,
        date.format(Date(entry.date)),
    ).joinToString(" · ")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) item { EmptyLibrary(R.string.history_empty, R.string.history_intro) }
            items(entries, key = { it.uri }) { entry ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaThumbnail(if (entry.missing) null else entry.uri, Modifier.size(52.dp)) {
                            PlatformBadge(entry.platform, Modifier.size(28.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(facts(entry), style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                PlatformBadge(entry.platform, Modifier.size(16.dp))
                                Text(stringResource(if (entry.missing) R.string.history_missing else R.string.queue_complete),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menu = true }, contentPadding = PaddingValues(4.dp)) {
                                Text(stringResource(R.string.history_actions), style = MaterialTheme.typography.labelMedium)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                @Composable fun action(label: Int, enabled: Boolean = true, click: () -> Unit) {
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = { menu = false; click() })
                                }
                                action(R.string.open_gallery, !entry.missing) { perform {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri), entry.mime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } }
                                action(R.string.history_share, !entry.missing) { perform {
                                    context.startActivity(mediaShareChooser(context, listOf(entry.uri)))
                                } }
                                action(R.string.history_again, entry.sourceUrl != null || !entry.missing) { perform {
                                    val msg = if (queue.redownload(entry.uri) != null) R.string.history_requeued else R.string.library_action_failed
                                    host.showSnackbar(context.getString(msg))
                                } }
                                action(R.string.history_info) { details = entry }
                                action(R.string.history_delete) { deletion = entry }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
    }
    details?.let { e ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(stringResource(R.string.history_info)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(e.title); Text(e.mime); Text(facts(e)); Text(e.uri) } },
            confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.close)) } })
    }
    deletion?.let { e ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text(stringResource(R.string.history_delete)) },
            text = { Text(stringResource(if (e.missing) R.string.history_forget_confirm else R.string.history_delete_confirm, e.title)) },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = { deletion = null; perform {
                if (!queue.deleteHistory(e.uri)) host.showSnackbar(context.getString(R.string.library_action_failed))
            } }) { Text(stringResource(R.string.history_delete)) } })
    }
}

@Composable
private fun EmptyLibrary(title: Int, hint: Int) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .75f)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
