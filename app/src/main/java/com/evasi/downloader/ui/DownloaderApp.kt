package com.evasi.downloader.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.evasi.downloader.R
import com.evasi.downloader.media.MediaKind

private enum class Tab(val label: Int, val icon: Mark) {
    Home(R.string.tab_home, Mark.Home), Downloads(R.string.tab_downloads, Mark.Download),
    History(R.string.tab_history, Mark.History), Settings(R.string.settings, Mark.Settings),
}

@Composable
internal fun DownloaderApp(
    state: ScreenState,
    appearance: AppAppearance,
    downloads: @Composable () -> Unit,
    history: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    postText: @Composable () -> Unit,
    onEvent: (UiEvent) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var handledShare by rememberSaveable { mutableLongStateOf(state.controlsGeneration) }
    LaunchedEffect(state.controlsGeneration, state.source) {
        if (state.source == Source.Share && state.controlsGeneration != handledShare) {
            handledShare = state.controlsGeneration
            tab = Tab.Home
        }
    }
    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
    EvasiTheme(appearance) {
        GlassBackdrop {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()
                .systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (appearance.compact) 6.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.brand_logo), null, Modifier.size(39.dp).clip(RoundedCornerShape(24)))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-.4).sp)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.Home -> Home(state, postText, { tab = Tab.Downloads }, onEvent)
                        Tab.Downloads -> downloads()
                        Tab.History -> history()
                        Tab.Settings -> settings()
                    }
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (appearance.glass) .85f else 1f),
                    windowInsets = WindowInsets(0), tonalElevation = 0.dp) {
                    Tab.entries.forEach { destination ->
                        val name = stringResource(destination.label)
                        NavigationBarItem(
                            selected = tab == destination, onClick = { tab = destination },
                            icon = { Glyph(destination.icon,
                                if (tab == destination) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                Modifier.size(22.dp)) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                            modifier = Modifier.semantics { contentDescription = name },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
            if (state.qualitySheet && tab == Tab.Home) QualityPanel(state, onEvent)
        }
    }
}

@Composable
private fun Home(
    state: ScreenState, completedShare: @Composable () -> Unit, showQueue: () -> Unit, onEvent: (UiEvent) -> Unit,
) {
    val compact = LocalAppearance.current.compact
    var help by rememberSaveable { mutableStateOf<Platform?>(null) }
    val ready = state.phase == Phase.Ready
    val preparing = state.phase == Phase.Preparing
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
        ) {
            if (state.phase == Phase.Saved) {
                MediaCard(state)
                completedShare()
                OutlinedButton(onClick = { onEvent(UiEvent.OpenGallery) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_gallery))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.new_download), Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold,
                    fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-1).sp)
                LinkField(state.link, onEvent)
                Platforms { help = it }
            }
            if (state.phase != Phase.Saved) MediaCard(state)
            when (state.phase) {
                Phase.Downloading -> OutlinedButton(onClick = { onEvent(UiEvent.Cancel) }) { Text(stringResource(R.string.cancel)) }
                Phase.Queued, Phase.Paused -> OutlinedButton(onClick = showQueue) { Text(stringResource(R.string.tab_downloads)) }
                Phase.Failed, Phase.Cancelled -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val canRetry = if (state.phase == Phase.Cancelled) {
                            state.source != Source.None || state.link.isNotBlank()
                        } else {
                            state.error !in setOf(UiError.Unsupported, UiError.InvalidLink, UiError.NoMedia)
                        }
                        if (canRetry)
                            OutlinedButton(onClick = { onEvent(UiEvent.Retry) }) { Text(stringResource(R.string.retry)) }
                        if (state.savedCount > 0) TextButton(onClick = { onEvent(UiEvent.OpenGallery) }) { Text(stringResource(R.string.open_gallery)) }
                    }
                }
                else -> Unit
            }
            Spacer(Modifier.height(1.dp))
        }
        if (state.phase == Phase.Idle || preparing || ready) {
            DownloadActions(state.quality, preparing || ready, ready, onEvent)
        }
    }
    help?.let { PlatformInfo(it) { help = null } }
}

@Composable
private fun LinkField(link: String, onEvent: (UiEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val paste = stringResource(R.string.paste_link)
    val clear = stringResource(R.string.clear_link)
    OutlinedTextField(
        value = link, onValueChange = { onEvent(UiEvent.EditLink(it)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp),
        label = { Text(stringResource(R.string.media_link)) },
        placeholder = { Text(stringResource(R.string.link_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Glyph(Mark.Link, colors.primary, Modifier.size(22.dp)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (link.isNotEmpty()) IconButton(onClick = { onEvent(UiEvent.EditLink("")) },
                    modifier = Modifier.semantics { contentDescription = clear }) {
                    Glyph(Mark.Close, colors.onSurfaceVariant, Modifier.size(17.dp))
                }
                IconButton(onClick = { focus.clearFocus(); onEvent(UiEvent.Paste) },
                    modifier = Modifier.semantics { contentDescription = paste }) {
                    Glyph(Mark.Clipboard, colors.primary, Modifier.size(20.dp))
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = colors.surface.copy(alpha = if (LocalAppearance.current.glass) .82f else 1f),
            focusedContainerColor = colors.surface, unfocusedBorderColor = colors.outlineVariant,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
    )
}

@Composable
private fun Platforms(onSelect: (Platform) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Platform.entries.forEach { platform ->
            IconButton(onClick = { onSelect(platform) }, modifier = Modifier.size(48.dp)) {
                PlatformBadge(platform.title, Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun MediaCard(state: ScreenState) {
    val colors = MaterialTheme.colorScheme
    val label = when (state.phase) {
        Phase.Idle -> R.string.preview
        Phase.Preparing -> R.string.preparing_title
        Phase.Ready -> R.string.ready_title
        Phase.Queued -> R.string.home_queue_waiting
        Phase.Paused -> R.string.transfer_paused
        Phase.Downloading -> R.string.download_title
        Phase.Saved -> R.string.saved_title
        Phase.Cancelled -> R.string.cancelled_title
        Phase.Failed -> R.string.failed_title
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (LocalAppearance.current.compact) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val success = state.phase == Phase.Saved
                var showSuccess by remember(state.phase) { mutableStateOf(false) }
                LaunchedEffect(success) { showSuccess = success }
                val scale by animateFloatAsState(
                    if (success && !showSuccess) .88f else 1f,
                    if (LocalAppearance.current.animations) tween(190) else snap(), label = "completion",
                )
                Box(Modifier.size(40.dp).scale(scale).background(colors.primaryContainer, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) {
                    Glyph(when (state.phase) {
                        Phase.Saved -> Mark.Check
                        Phase.Failed, Phase.Cancelled -> Mark.Repeat
                        Phase.Paused, Phase.Queued -> Mark.History
                        Phase.Downloading, Phase.Ready -> Mark.Download
                        Phase.Idle, Phase.Preparing -> Mark.Gallery
                    }, colors.primary, Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(label), Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                        color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (state.phase == Phase.Idle) Text(stringResource(R.string.preview_hint), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.link.isNotEmpty() && state.phase != Phase.Idle)
                    PlatformBadge(state.link, Modifier.size(30.dp))
            }
            if (state.previewUri != null) {
                MediaThumbnail(state.previewUri, Modifier.fillMaxWidth().heightIn(max = 220.dp).aspectRatio(16f / 9)
                    .clip(RoundedCornerShape(15.dp)).background(colors.surfaceVariant)) {
                    Glyph(Mark.Gallery, colors.onSurfaceVariant, Modifier.size(32.dp))
                }
            }
            val name = state.fileName.ifBlank { state.title }
            if (name.isNotBlank() && state.phase != Phase.Idle) {
                Text(name, color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val details = listOfNotNull(
                state.mediaKind?.let { stringResource(when (it) {
                    MediaKind.PHOTO -> R.string.media_photo
                    MediaKind.GIF -> R.string.media_gif
                    MediaKind.VIDEO -> R.string.media_video
                }) },
                state.resolution,
                if (state.total > 1) pluralStringResource(R.plurals.album_count, state.total, state.total) else null,
            )
            if (details.isNotEmpty()) Text(details.joinToString("  ·  "), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            when (state.phase) {
                Phase.Preparing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Phase.Downloading, Phase.Saved -> TransferDetails(state)
                Phase.Failed -> Text(stringResource(errorMessage(state.error)), color = colors.error)
                else -> Unit
            }
            if (state.savedCount > 0) Text(pluralStringResource(R.plurals.saved_items, state.savedCount, state.savedCount),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransferDetails(state: ScreenState) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val fraction = state.progress?.coerceIn(0f, 1f)
    val done = state.phase == Phase.Saved
    if (!done) {
        if (state.total > 1 && state.item > 0) Text(stringResource(R.string.item_progress, state.item, state.total), color = colors.onSurfaceVariant)
        if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else {
            val progress by animateFloatAsState(fraction, if (LocalAppearance.current.animations) tween(140) else snap(), label = "transfer")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
    val downloaded = Formatter.formatShortFileSize(context, state.downloadedBytes)
    val size = state.totalBytes?.let { Formatter.formatShortFileSize(context, it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (done) size ?: downloaded else if (size == null) stringResource(R.string.downloaded_value, downloaded)
            else stringResource(R.string.transfer_amount, downloaded, size),
            color = colors.onSurface, fontWeight = FontWeight.SemiBold)
        if (fraction != null && !done) Text(stringResource(R.string.transfer_percent, fraction * 100), color = colors.primary)
    }
    if (!done) {
        val info = listOfNotNull(
            if (size == null) stringResource(R.string.total_unknown) else null,
            state.bytesPerSecond?.takeIf { it > 0 }?.let { stringResource(R.string.transfer_speed, Formatter.formatShortFileSize(context, it)) },
            state.remainingSeconds?.let { stringResource(R.string.transfer_eta, android.text.format.DateUtils.formatElapsedTime(it)) },
        )
        if (info.isNotEmpty()) Text(info.joinToString("  ·  "), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DownloadActions(quality: String?, canSelect: Boolean, canStart: Boolean, onEvent: (UiEvent) -> Unit) {
    val focus = LocalFocusManager.current
    val event by rememberUpdatedState(onEvent)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (LocalAppearance.current.compact) 5.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val select = stringResource(R.string.select_quality)
        val qualityTouch = if (canSelect) Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                event(UiEvent.QualityTouched)
            }
        } else Modifier
        OutlinedButton(
            onClick = { focus.clearFocus(); onEvent(UiEvent.OpenQuality) }, enabled = canSelect,
            modifier = Modifier.weight(1f).heightIn(min = 50.dp).then(qualityTouch)
                .semantics { if (quality != null) contentDescription = select },
            contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(16.dp),
        ) { Text(quality ?: select) }
        Button(onClick = { focus.clearFocus(); onEvent(UiEvent.Start) }, enabled = canStart,
            modifier = Modifier.weight(1.12f).heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Glyph(Mark.Download, LocalContentColor.current, Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.start_download))
        }
    }
}

@Composable
private fun QualityPanel(state: ScreenState, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    InfoPanel(stringResource(R.string.select_quality), { onEvent(UiEvent.CloseQuality) }) {
        if (state.resolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.quality_finding))
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).selectableGroup()) {
            item(key = "original") {
                QualityRow(stringResource(R.string.quality_best), state.quality == null, null) { onEvent(UiEvent.SelectOriginal) }
            }
            items(state.qualityOptions, key = { it }) { option ->
                QualityRow(option, state.quality == option,
                    state.qualitySizes[option]?.let { Formatter.formatShortFileSize(context, it) }) {
                    onEvent(UiEvent.SelectQuality(option))
                }
            }
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, size: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 11.dp, horizontal = 4.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f))
        if (size != null) Text(size, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoPanel(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val panel = @Composable {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            content()
            TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
        }
    }
    if (LocalAppearance.current.animations) ModalBottomSheet(
        onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { panel() } else Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) { panel() }
    }
}

private fun errorMessage(error: UiError?): Int = when (error) {
    UiError.Network -> R.string.error_network
    UiError.Unavailable -> R.string.error_unavailable
    UiError.Unsupported -> R.string.error_unsupported
    UiError.NoMedia -> R.string.error_no_media
    UiError.Read -> R.string.error_read
    UiError.Write -> R.string.error_write
    UiError.InvalidLink -> R.string.error_invalid_link
    UiError.Gallery -> R.string.gallery_unavailable
    UiError.Deleted -> R.string.home_deleted
    UiError.Private -> R.string.home_private
    UiError.Timeout -> R.string.home_timeout
    UiError.NoSpace -> R.string.home_no_space
    UiError.Permission -> R.string.home_permission
    UiError.Unknown, null -> R.string.error_unknown
}
