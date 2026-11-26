package com.evasi.downloader.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.evasi.downloader.R
import com.evasi.downloader.auth.LoginIssue
import com.evasi.downloader.auth.XAccounts
import com.evasi.downloader.auth.XSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CompletedShare(
    uris: List<String>,
    originalText: String?,
    modifier: Modifier = Modifier,
    account: XSession = XAccounts.get(LocalContext.current),
) {
    if (uris.isEmpty()) return
    val context = LocalContext.current
    val state by account.account.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { account.authorized() } }
    var signIn by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    LaunchedEffect(state.username) { if (state.username != null) signIn = false }
    fun share(text: String?) {
        if (preparing) return
        preparing = true
        scope.launch {
            try {
                val intent = if (text == null) mediaShareChooser(context, uris)
                    else captionedShareChooser(context, uris, text, account)
                context.startActivity(intent)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (text != null && !account.authorized()) signIn = true
                else Toast.makeText(context, R.string.share_files_unavailable, Toast.LENGTH_SHORT).show()
            } finally { preparing = false }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val username = state.username
        val caption = originalText.orEmpty()
        if (username != null && caption.isNotBlank()) key(uris, username, caption) {
            var draft by rememberSaveable { mutableStateOf(caption) }
            val video = uris.size == 1 && Uri.parse(uris.single()).pathSegments.contains("video")
            Text(stringResource(if (video) R.string.share_video_caption_title else R.string.share_caption_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                label = { Text(stringResource(R.string.share_original_text)) })
            Button(onClick = { share(draft) }, enabled = !preparing && draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_with_text))
            }
            OutlinedButton(onClick = {
                scope.launch {
                    if (!account.authorized()) { signIn = true; return@launch }
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val copied = try {
                        if (clipboard == null) false else {
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.share_original_text), draft))
                            true
                        }
                    } catch (_: SecurityException) { false }
                    Toast.makeText(context, if (copied) R.string.share_copied else R.string.share_copy_failed, Toast.LENGTH_SHORT).show()
                }
            }, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_copy_text)) }
            TextButton(onClick = { share(null) }, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (video) R.string.share_video_only else R.string.share_file_only))
            }
        } else {
            Button(onClick = { share(null) }, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_media_button))
            }
            if (username == null) {
                TextButton(onClick = { signIn = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.share_with_text))
                }
            } else Text(stringResource(R.string.share_no_caption), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (signIn && state.username == null) AlertDialog(
        onDismissRequest = { signIn = false },
        title = { Text(stringResource(R.string.x_login)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.x_login_explanation))
            if (!account.config.available) Text(stringResource(R.string.x_login_unconfigured))
            state.issue?.let { Text(stringResource(issueResource(it))) }
        } },
        confirmButton = { if (account.config.available) TextButton(onClick = {
            scope.launch { openLogin(context, account) }
        }, enabled = !state.checking) { Text(stringResource(R.string.x_login)) } },
        dismissButton = { TextButton(onClick = { signIn = false }) { Text(stringResource(R.string.share_close)) } },
    )
}

@Composable
internal fun XAccountSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val account = remember(context) { XAccounts.get(context) }
    val state by account.account.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { account.authorized() } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.x_account), style = MaterialTheme.typography.titleMedium)
        val username = state.username
        if (username != null) {
            Text(stringResource(R.string.x_logged_as, username))
            OutlinedButton(onClick = { scope.launch { account.logout() } }) { Text(stringResource(R.string.x_logout)) }
        } else {
            Text(stringResource(R.string.x_login_explanation))
            if (account.config.available) {
                OutlinedButton(onClick = { scope.launch { openLogin(context, account) } }, enabled = !state.checking) {
                    Text(stringResource(if (state.checking) R.string.x_verifying else R.string.x_login))
                }
            } else Text(stringResource(R.string.x_login_unconfigured), style = MaterialTheme.typography.bodyMedium)
        }
        state.issue?.let { Text(stringResource(issueResource(it)), style = MaterialTheme.typography.bodyMedium) }
    }
}

private suspend fun openLogin(context: Context, account: XSession) {
    val address = account.start() ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.toString())).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) { account.browserUnavailable() }
    catch (_: SecurityException) { account.browserUnavailable() }
}

private fun issueResource(issue: LoginIssue): Int = when (issue) {
    LoginIssue.EXPIRED -> R.string.x_session_expired
    LoginIssue.DENIED -> R.string.x_login_denied
    LoginIssue.CONNECTION -> R.string.x_login_failed
    LoginIssue.STORAGE -> R.string.x_storage_failed
    LoginIssue.BROWSER -> R.string.x_no_browser
}
