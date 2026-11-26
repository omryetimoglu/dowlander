package com.evasi.downloader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evasi.downloader.AppSettings
import com.evasi.downloader.BuildConfig
import com.evasi.downloader.R
import com.evasi.downloader.qualityLimits
import com.evasi.downloader.share.XAccountSettings
import com.evasi.downloader.validAlbumName
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val projectUrl = "https://github.com/omryetimoglu/dowlander"
private const val updateMetadata =
    "https://raw.githubusercontent.com/omryetimoglu/dowlander/main/app/build.gradle.kts"

internal data class AvailableVersion(val code: Int, val name: String)

internal fun parseAvailableVersion(source: String): AvailableVersion? {
    val code = Regex("""(?m)^\s*versionCode\s*=\s*(\d+)\s*$""").find(source)
        ?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val name = Regex("""(?m)^\s*versionName\s*=\s*"([^"\r\n]{1,60})"\s*$""").find(source)
        ?.groupValues?.get(1) ?: return null
    return AvailableVersion(code, name)
}

private suspend fun checkVersion(): AvailableVersion = withContext(Dispatchers.IO) {
    val connection = URL(updateMetadata).openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200) throw IOException()
        val buffer = ByteArray(96 * 1024)
        val bytes = connection.inputStream.use { input ->
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count == buffer.size) throw IOException()
            count
        }
        parseAvailableVersion(String(buffer, 0, bytes, Charsets.UTF_8)) ?: throw IOException()
    } finally {
        connection.disconnect()
    }
}

private fun Context.openSettingsIntent(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.pref_no_app, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.pref_no_app, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun PreferencesScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onNotificationSettings: () -> Unit,
    onClearHistory: suspend () -> Unit,
    onClearCache: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var available by rememberSaveable { mutableStateOf("") }
    val style = settings.appearance
    fun appearance(change: (AppAppearance) -> AppAppearance) = onChange { it.copy(appearance = change(it.appearance)) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (style.compact) 10.dp else 16.dp)) {
        Group(stringResource(R.string.pref_appearance)) {
            Choice(stringResource(R.string.language), settings.language, listOf(
                "en" to stringResource(R.string.english), "tr" to stringResource(R.string.turkish)
            )) { lang -> onChange { it.copy(language = lang) } }
            Choice(stringResource(R.string.pref_theme), style.theme, listOf(
                "system" to stringResource(R.string.pref_system), "light" to stringResource(R.string.pref_light),
                "dark" to stringResource(R.string.pref_dark)
            )) { theme -> appearance { it.copy(theme = theme) } }
            Choice(stringResource(R.string.pref_accent), style.accent, listOf(
                "blue" to stringResource(R.string.pref_blue), "purple" to stringResource(R.string.pref_purple),
                "teal" to stringResource(R.string.pref_teal), "rose" to stringResource(R.string.pref_rose)
            )) { color -> appearance { it.copy(accent = color) } }
            Toggle(stringResource(R.string.pref_glass), style.glass) { on -> appearance { it.copy(glass = on) } }
            if (style.glass) Choice(stringResource(R.string.pref_blur), style.blur, listOf(
                0f to stringResource(R.string.pref_none), .25f to "25%", .5f to "50%", .75f to "75%", 1f to "100%"
            )) { blur -> appearance { it.copy(blur = blur) } }
            Toggle(stringResource(R.string.pref_animation), style.animations) { on -> appearance { it.copy(animations = on) } }
            Choice(stringResource(R.string.pref_layout), style.compact, listOf(
                false to stringResource(R.string.pref_comfortable), true to stringResource(R.string.pref_compact)
            )) { compact -> appearance { it.copy(compact = compact) } }
        }
        Group(stringResource(R.string.download_settings)) {
            Toggle(stringResource(R.string.pref_automatic), settings.autoStart,
                stringResource(R.string.pref_automatic_hint)) { on -> onChange { it.copy(autoStart = on) } }
            val highest = stringResource(R.string.pref_highest)
            Choice(stringResource(R.string.pref_quality), settings.defaultQuality,
                qualityLimits.map { it to if (it == 0) highest else "${it}p" }) { quality ->
                onChange { it.copy(defaultQuality = quality) }
            }
            ActionRow(stringResource(R.string.pref_location), stringResource(R.string.pref_album_paths, settings.album)) {
                dialog = R.string.pref_location
            }
            Toggle(stringResource(R.string.pref_wifi), settings.wifiOnly) { on -> onChange { it.copy(wifiOnly = on) } }
            Choice(stringResource(R.string.pref_concurrency), settings.concurrentDownloads,
                (1..3).map { it to it.toString() }) { count -> onChange { it.copy(concurrentDownloads = count) } }
            ActionRow(stringResource(R.string.notification_settings), stringResource(R.string.notification_settings_hint), onClick = onNotificationSettings)
        }
        XAccountSettings()
        Group(stringResource(R.string.pref_storage)) {
            ActionRow(stringResource(R.string.pref_clear_history), enabled = !clearing) { dialog = R.string.pref_clear_history }
            ActionRow(stringResource(R.string.pref_clear_cache), enabled = !clearing) { dialog = R.string.pref_clear_cache }
        }
        Group(stringResource(R.string.pref_contact)) {
            for (action in listOf(R.string.pref_support, R.string.pref_problem, R.string.pref_feature)) {
                val subject = stringResource(action)
                ActionRow(subject) {
                    val email = context.getString(R.string.support_email).trim()
                    if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        dialog = R.string.pref_contact_unset
                    } else {
                        val uri = Uri.parse("mailto:${Uri.encode(email, "@")}?subject=${Uri.encode(subject)}")
                        context.openSettingsIntent(Intent(Intent.ACTION_SENDTO, uri))
                    }
                }
            }
        }
        Group(stringResource(R.string.pref_about)) {
            ActionRow(stringResource(R.string.pref_about_app)) { dialog = R.string.pref_about_app }
            ActionRow(stringResource(R.string.pref_privacy)) { dialog = R.string.pref_privacy }
            ActionRow(stringResource(R.string.pref_terms)) { dialog = R.string.pref_terms }
            LabelRow(stringResource(R.string.pref_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            ActionRow(stringResource(if (checking) R.string.pref_checking else R.string.pref_check_updates), enabled = !checking) {
                checking = true
                scope.launch {
                    try {
                        val online = checkVersion()
                        available = online.name
                        dialog = if (online.code > BuildConfig.VERSION_CODE) R.string.pref_update_available else R.string.pref_up_to_date
                    } catch (_: IOException) {
                        dialog = R.string.pref_update_error
                    } finally {
                        checking = false
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    val active = dialog ?: return
    if (active == R.string.pref_location) {
        var album by rememberSaveable { mutableStateOf(settings.album) }
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(active)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pref_album_hint))
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.pref_album_name)) },
                    isError = !validAlbumName(album), singleLine = true)
                if (!validAlbumName(album)) Text(stringResource(R.string.pref_album_error), color = MaterialTheme.colorScheme.error)
            } }, confirmButton = {
                TextButton(enabled = validAlbumName(album), onClick = { onChange { it.copy(album = album.trim()) }; dialog = null }) {
                    Text(stringResource(R.string.pref_apply))
                }
            }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        return
    }
    val message = when (active) {
        R.string.pref_privacy -> stringResource(R.string.pref_privacy_body)
        R.string.pref_terms -> stringResource(R.string.pref_terms_body)
        R.string.pref_about_app -> stringResource(R.string.pref_about_body, BuildConfig.VERSION_NAME)
        R.string.pref_contact_unset -> stringResource(R.string.pref_contact_unset_body)
        R.string.pref_clear_history -> stringResource(R.string.pref_history_confirm)
        R.string.pref_clear_cache -> stringResource(R.string.pref_cache_confirm)
        R.string.pref_update_available -> stringResource(R.string.pref_update_body, available)
        R.string.pref_up_to_date -> stringResource(R.string.pref_current_body, BuildConfig.VERSION_NAME)
        else -> stringResource(R.string.pref_update_error_body)
    }
    val confirmLabel = when (active) {
        R.string.pref_clear_history, R.string.pref_clear_cache -> R.string.pref_clear
        R.string.pref_update_available -> R.string.pref_view_update
        else -> R.string.close
    }
    AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(active)) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            when (active) {
                R.string.pref_clear_history, R.string.pref_clear_cache -> {
                    clearing = true
                    scope.launch {
                        try {
                            val messageId = if (active == R.string.pref_clear_history) {
                                onClearHistory()
                                R.string.pref_history_cleared
                            } else {
                                onClearCache()
                                R.string.pref_cache_cleared
                            }
                            Toast.makeText(context, messageId, Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.pref_clear_error, Toast.LENGTH_LONG).show()
                        } finally {
                            clearing = false
                        }
                    }
                }
                R.string.pref_update_available -> {
                    // The published APK updates the GitHub package. Studio installs use the source project.
                    val url = if (context.packageName.endsWith(".xtest")) "$projectUrl/blob/main/apk/eVasiApp.apk" else projectUrl
                    context.openSettingsIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            dialog = null
        }) { Text(stringResource(confirmLabel)) } },
        dismissButton = if (confirmLabel == R.string.close) null else ({
            TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) }
        }))
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, Modifier.padding(start = 9.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        GlassCard(Modifier.fillMaxWidth(), content)
    }
}

@Composable
private fun LabelRow(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = if (LocalAppearance.current.compact) 48.dp else 56.dp)
        .padding(horizontal = 17.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    LabelRow(title, subtitle, Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}

@Composable
private fun Toggle(title: String, enabled: Boolean, subtitle: String = "", change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = change)
        .padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LabelRow(title, subtitle, Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun <T> Choice(title: String, current: T, options: List<Pair<T, String>>, select: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ActionRow(title, options.firstOrNull { it.first == current }?.second.orEmpty()) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = value == current, role = Role.RadioButton, onClick = { open = false; select(value) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == current, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label)
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}
