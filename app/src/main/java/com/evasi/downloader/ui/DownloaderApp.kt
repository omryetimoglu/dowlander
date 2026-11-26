package com.evasi.downloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evasi.downloader.R
import kotlin.math.roundToInt

private val palette = lightColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = Mint, onPrimaryContainer = Ink,
    surface = Color.White, onSurface = Ink, onSurfaceVariant = Muted, background = Paper,
    onBackground = Ink, outline = Line, secondaryContainer = Mint,
)

@Composable
fun DownloaderApp(state: ScreenState, onEvent: (UiEvent) -> Unit) {
    var settings by rememberSaveable(state.controlsGeneration) { mutableStateOf(false) }
    var languagePanel by rememberSaveable(state.controlsGeneration) { mutableStateOf(false) }
    BackHandler(enabled = settings && !languagePanel) { settings = false }
    MaterialTheme(colorScheme = palette) {
        Box(Modifier.fillMaxSize().background(Paper).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 580.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (settings) {
                        IconAction(Mark.Back, R.string.back) { settings = false }
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Box(Modifier.padding(start = 4.dp).size(38.dp).background(Ink, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Glyph(Mark.Logo, Color(0xFFB5F5CC), Modifier.size(23.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        stringResource(if (settings) R.string.settings else R.string.app_name),
                        Modifier.weight(1f), color = Ink, fontSize = 18.sp, lineHeight = 23.sp,
                        letterSpacing = (-0.5).sp, fontWeight = FontWeight.Bold,
                    )
                    if (!settings) IconAction(Mark.Settings, R.string.settings) { settings = true }
                }
                HorizontalDivider(color = Line)
                if (settings) SettingsContent(state, onEvent) { languagePanel = true }
                else Home(state, onEvent)
            }
        }
        if (languagePanel && settings) LanguagePanel(state.language, { languagePanel = false }) {
            languagePanel = false
            onEvent(UiEvent.SetLanguage(it))
        }
        if (!settings && state.qualitySheet) QualityPanel(state, onEvent)
    }
}

@Composable
private fun IconAction(mark: Mark, description: Int, action: () -> Unit) {
    val label = stringResource(description)
    IconButton(onClick = action, modifier = Modifier.semantics { contentDescription = label }) {
        Glyph(mark, Ink, Modifier.size(22.dp))
    }
}

@Composable
private fun ColumnScope.Home(state: ScreenState, onEvent: (UiEvent) -> Unit) {
    val picking = state.source == Source.Clipboard && state.phase in listOf(Phase.Preparing, Phase.Ready)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        // Keep the main action next to the status. The whole group can scroll on short
        // screens and with large system fonts.
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            val title = when (state.phase) {
                Phase.Idle -> R.string.idle_title
                Phase.Preparing -> R.string.preparing_title
                Phase.Ready -> R.string.ready_title
                Phase.Downloading -> if (state.resolving) R.string.preparing_title else R.string.download_title
                Phase.Saved -> R.string.saved_title
                Phase.Failed -> R.string.failed_title
                Phase.Cancelled -> R.string.cancelled_title
            }
            val mark = when (state.phase) {
                Phase.Idle, Phase.Downloading -> Mark.Download
                Phase.Preparing, Phase.Ready -> Mark.Link
                Phase.Saved -> Mark.Check
                Phase.Failed -> Mark.Repeat
                Phase.Cancelled -> Mark.Close
            }
            val hint = when (state.phase) {
                Phase.Idle -> stringResource(R.string.idle_intro)
                Phase.Failed -> stringResource(errorMessage(state.error))
                Phase.Saved -> savedText(state.savedCount)
                else -> ""
            }
            Box(Modifier.size(64.dp).background(Mint, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Glyph(mark, Accent, Modifier.size(31.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(title), Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                color = Ink, fontSize = 30.sp, lineHeight = 36.sp, textAlign = TextAlign.Center,
                letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(hint, Modifier.widthIn(max = 340.dp), color = Muted, textAlign = TextAlign.Center,
                    fontSize = 16.sp, lineHeight = 23.sp)
            }
            if (state.source != Source.None && state.phase !in listOf(Phase.Idle, Phase.Saved)) {
                Spacer(Modifier.height(18.dp))
                SourceCard(state)
            }
            if (state.phase == Phase.Downloading) {
                Spacer(Modifier.height(12.dp))
                TransferProgress(state)
            }
            if (state.savedCount > 0 && state.phase in listOf(Phase.Cancelled, Phase.Failed)) {
                Spacer(Modifier.height(8.dp))
                Text(savedText(state.savedCount), color = Muted)
            }
            if (!picking) Column(Modifier.padding(top = 24.dp).widthIn(max = 440.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    Phase.Idle -> Action(R.string.paste_link, Mark.Clipboard) { onEvent(UiEvent.Paste) }
                    Phase.Preparing, Phase.Ready, Phase.Downloading ->
                        Action(R.string.cancel, Mark.Close, primary = false) { onEvent(UiEvent.Cancel) }
                    Phase.Saved -> {
                        Action(R.string.open_gallery, Mark.Gallery) { onEvent(UiEvent.OpenGallery) }
                        Action(R.string.paste_link, Mark.Clipboard, primary = false) { onEvent(UiEvent.Paste) }
                    }
                    Phase.Failed, Phase.Cancelled -> {
                        val canRetry = state.phase == Phase.Cancelled ||
                            state.error !in listOf(UiError.InvalidLink, UiError.Unsupported, UiError.NoMedia)
                        if (canRetry) Action(R.string.retry, Mark.Repeat) { onEvent(UiEvent.Retry) }
                        if (state.savedCount > 0)
                            Action(R.string.open_gallery, Mark.Gallery, primary = false) { onEvent(UiEvent.OpenGallery) }
                        Action(R.string.paste_link, Mark.Clipboard, primary = !canRetry) { onEvent(UiEvent.Paste) }
                    }
                }
            }
        }
    }
    if (picking) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.countdown?.let {
                Text(stringResource(R.string.countdown_hint), color = Muted, fontSize = 13.sp)
                Rail(it)
            }
            ClipboardActions(state, onEvent)
        }
    }
}

@Composable
private fun savedText(count: Int): String =
    if (count == 0) "" else pluralStringResource(R.plurals.saved_items, count, count)

private fun errorMessage(error: UiError?): Int = when (error) {
    UiError.Network -> R.string.error_network
    UiError.Unavailable -> R.string.error_unavailable
    UiError.Unsupported -> R.string.error_unsupported
    UiError.NoMedia -> R.string.error_no_media
    UiError.Read -> R.string.error_read
    UiError.Write -> R.string.error_write
    UiError.InvalidLink -> R.string.error_invalid_link
    UiError.Gallery -> R.string.gallery_unavailable
    UiError.Unknown, null -> R.string.error_unknown
}

@Composable
private fun SourceCard(state: ScreenState) {
    val fromShare = state.source == Source.Share
    val url = state.link.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
    Surface(modifier = Modifier.widthIn(max = 440.dp), color = Color.White,
        border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Glyph(if (fromShare) Mark.Share else Mark.Link, Accent, Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(if (fromShare) R.string.share_label else R.string.clipboard_label),
                    color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (state.title.isNotBlank() && state.title != url) Text(state.title, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (url != null) Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted,
                    fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun TransferProgress(state: ScreenState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.item > 0 && state.total > 0) Text(
                stringResource(R.string.item_progress, state.item, state.total),
                color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            state.progress?.let {
                Text(stringResource(R.string.percentage, (it.coerceIn(0f, 1f) * 100).roundToInt()),
                    color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Rail(state.progress)
    }
}

@Composable
private fun Rail(progress: Float?) {
    if (progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = Accent, trackColor = Mint)
    } else {
        val fraction = progress.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Mint).progressSemantics(fraction)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent, CircleShape))
        }
    }
}

@Composable
private fun ClipboardActions(state: ScreenState, onEvent: (UiEvent) -> Unit) {
    var leftPlaced by remember(state.controlsGeneration) { mutableStateOf(false) }
    var rightPlaced by remember(state.controlsGeneration) { mutableStateOf(false) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val event by rememberUpdatedState(onEvent)
    val active = leftPlaced && rightPlaced && focused && !state.qualitySheet
    DisposableEffect(state.controlsGeneration, active) {
        val generation = state.controlsGeneration
        if (active) event(UiEvent.ControlsReady(generation))
        onDispose { if (active) event(UiEvent.ControlsHidden(generation)) }
    }
    BoxWithConstraints {
        val showIcons = maxWidth >= 355.dp && LocalDensity.current.fontScale <= 1.15f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom) {
            val qualityModifier = Modifier.weight(1f).onGloballyPositioned {
                leftPlaced = !it.boundsInWindow().isEmpty
            }.pointerInput(state.controlsGeneration) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    event(UiEvent.QualityTouched)
                }
            }
            Action(R.string.select_quality, if (showIcons) Mark.Sliders else null, qualityModifier,
                primary = false, label = state.quality) { onEvent(UiEvent.OpenQuality) }
            Action(R.string.start_download, if (showIcons) Mark.Download else null,
                Modifier.weight(1f).onGloballyPositioned {
                    rightPlaced = !it.boundsInWindow().isEmpty
                }) { onEvent(UiEvent.Start) }
        }
    }
}

@Composable
private fun Action(
    resource: Int, icon: Mark?, modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = true, label: String? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val foreground = if (primary) Color.White else Ink
    Row(
        modifier.heightIn(min = 56.dp).clip(shape)
            .background(if (primary) Accent else Color.White)
            .then(if (primary) Modifier else Modifier.border(1.dp, Line, shape))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, foreground, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label ?: stringResource(resource), color = foreground, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Panel(title: Int, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper, contentColor = Ink,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 4.dp).size(38.dp, 4.dp).background(Line, CircleShape)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f).semantics { heading() }, color = Ink,
                    fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 28.sp)
                IconAction(Mark.Close, R.string.close, dismiss)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun QualityPanel(state: ScreenState, onEvent: (UiEvent) -> Unit) {
    Panel(R.string.select_quality, { onEvent(UiEvent.CloseQuality) }) {
        when {
            state.resolving -> {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                    Text(stringResource(R.string.quality_finding), color = Muted)
                }
            }
            state.error != null -> Text(stringResource(errorMessage(state.error)), color = Muted)
            state.qualityOptions.isEmpty() -> Text(stringResource(R.string.quality_original), color = Muted)
            else -> {
                Text(stringResource(R.string.quality_hint), color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(state.qualityOptions) { option ->
                        ChoiceRow(option, option == state.quality) { onEvent(UiEvent.SelectQuality(option)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(state: ScreenState, onEvent: (UiEvent) -> Unit, openLanguage: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(color = Color.White, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(18.dp)) {
            SettingRow(Mark.Globe, stringResource(R.string.language),
                stringResource(if (state.language == "tr") R.string.turkish else R.string.english),
                Modifier.clickable(role = Role.Button, onClick = openLanguage)) {
                Glyph(Mark.Chevron, Muted, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        SectionTitle(R.string.download_settings)
        Surface(color = Color.White, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(18.dp)) {
            Column {
                SettingRow(Mark.Download, stringResource(R.string.auto_start), stringResource(R.string.auto_start_hint),
                    Modifier.toggleable(value = state.autoStart, role = Role.Switch,
                        onValueChange = { onEvent(UiEvent.SetAutoStart(it)) })) {
                    Switch(checked = state.autoStart, onCheckedChange = null)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Line)
                SettingRow(Mark.Bell, stringResource(R.string.notification_settings),
                    stringResource(R.string.notification_settings_hint),
                    Modifier.clickable(role = Role.Button) { onEvent(UiEvent.NotificationSettings) }) {
                    Glyph(Mark.Chevron, Muted, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(id: Int) {
    Text(stringResource(id), Modifier.semantics { heading() }, color = Muted,
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingRow(mark: Mark, title: String, subtitle: String, modifier: Modifier, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(mark, Accent, Modifier.size(21.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        trailing()
    }
}

@Composable
private fun LanguagePanel(current: String, dismiss: () -> Unit, select: (String) -> Unit) {
    Panel(R.string.language, dismiss) {
        ChoiceRow(stringResource(R.string.english), current == "en") { select("en") }
        ChoiceRow(stringResource(R.string.turkish), current == "tr") { select("tr") }
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Mint else Color.Transparent)
            .selectable(selected = selected, onClick = choose, role = Role.RadioButton)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(22.dp))
    }
}
