package com.evasi.downloader.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.evasi.downloader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class Platform(val title: String, val icon: Int, val tint: Color, val example: String, val types: Int) {
    Instagram("Instagram", R.drawable.platform_instagram, Color(0xFFC42B7D), "instagram.com/p/…", R.string.platform_albums),
    TikTok("TikTok", R.drawable.platform_tiktok, Color(0xFF13131B), "tiktok.com/@…/video/…", R.string.platform_clips_photos),
    X("X", R.drawable.platform_twitter_x, Color(0xFF14151C), "x.com/…/status/…", R.string.platform_mixed),
    YouTube("YouTube", R.drawable.platform_youtube, Color(0xFFE32024), "youtube.com/watch?v=…", R.string.platform_video_shorts),
    Facebook("Facebook", R.drawable.platform_facebook, Color(0xFF0866FF), "facebook.com/…/posts/…", R.string.platform_albums),
    Threads("Threads", R.drawable.platform_threads, Color(0xFF161620), "threads.com/@…/post/…", R.string.platform_albums),
    Mastodon("Mastodon", R.drawable.platform_mastodon, Color(0xFF615CDB), "mastodon.social/@…/…", R.string.platform_mixed);

    companion object {
        fun from(source: String): Platform? {
            entries.firstOrNull { it.title.equals(source, true) }?.let { return it }
            val uri = Uri.parse(source)
            val host = uri.host?.lowercase() ?: return null
            fun matches(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                matches("instagram.com") || matches("instagr.am") -> Instagram
                matches("tiktok.com") -> TikTok
                matches("x.com") || matches("twitter.com") -> X
                matches("youtu.be") || matches("youtube.com") -> YouTube
                matches("facebook.com") || matches("fb.watch") -> Facebook
                matches("threads.com") || matches("threads.net") -> Threads
                uri.path.orEmpty().let { it.startsWith("/@") || "/statuses/" in it } -> Mastodon
                else -> null
            }
        }
    }
}

@Composable
internal fun PlatformBadge(source: String, modifier: Modifier = Modifier) {
    val platform = remember(source) { Platform.from(source) }
    Box(modifier.background(Color.White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (platform == null) Glyph(Mark.Link, Color(0xFF465070), Modifier.fillMaxSize(.55f))
        else Icon(painterResource(platform.icon), platform.title, Modifier.fillMaxSize(.62f), tint = platform.tint)
    }
}

@Composable
internal fun MediaThumbnail(uri: String?, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val value = remember(uri) { uri?.let(Uri::parse) }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (value?.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                var bitmap by remember(value) { mutableStateOf<ImageBitmap?>(null) }
                val signal = remember(value) { CancellationSignal() }
                DisposableEffect(signal) { onDispose { signal.cancel() } }
                LaunchedEffect(value) {
                    bitmap = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.loadThumbnail(value, Size(480, 360), signal).asImageBitmap()
                        } catch (_: IOException) { null }
                        catch (_: SecurityException) { null }
                        catch (_: IllegalArgumentException) { null }
                        catch (_: OperationCanceledException) { null }
                    }
                }
                val loaded = bitmap
                if (loaded == null) fallback()
                else Image(loaded, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            "https" -> SubcomposeAsyncImage(
                model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
            )
            else -> fallback()
        }
    }
}

@Composable
internal fun PlatformInfo(platform: Platform, close: () -> Unit) {
    InfoPanel(platform.title, close) {
        PlatformBadge(platform.title, Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(platform.types), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.platform_public), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.example_link), style = MaterialTheme.typography.labelMedium)
        Text(platform.example, color = MaterialTheme.colorScheme.primary)
    }
}
