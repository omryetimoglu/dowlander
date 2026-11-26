package com.evasi.downloader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

data class AppAppearance(
    val theme: String = "system",
    val accent: String = "blue",
    val glass: Boolean = true,
    val blur: Float = 0.5f,
    val animations: Boolean = true,
    val compact: Boolean = false,
)

internal val LocalAppearance = staticCompositionLocalOf { AppAppearance() }

@Composable
internal fun EvasiTheme(appearance: AppAppearance, content: @Composable () -> Unit) {
    val dark = when (appearance.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = remember(dark, appearance.accent) {
        val accent = when (appearance.accent) {
            "purple" -> if (dark) Color(0xFFCCB8FF) else Color(0xFF7142B5)
            "teal" -> if (dark) Color(0xFF7CDACE) else Color(0xFF006C65)
            "rose" -> if (dark) Color(0xFFFFAECB) else Color(0xFFA33767)
            else -> if (dark) Color(0xFFB4C4FF) else Color(0xFF3C52B0)
        }
        if (dark) darkColorScheme(
            primary = accent, onPrimary = Color(0xFF14192B), primaryContainer = lerp(Color(0xFF1B2234), accent, .19f),
            onPrimaryContainer = accent, secondary = accent, secondaryContainer = Color(0xFF303448),
            onSecondaryContainer = Color(0xFFE9EAFF), surface = Color(0xFF1A1E2E),
            onSurface = Color(0xFFF1F1F8), onSurfaceVariant = Color(0xFFB8BED0),
            surfaceVariant = Color(0xFF2A3043), background = Color(0xFF111524), onBackground = Color(0xFFF1F1F8),
            outline = Color(0xFF9099B1), outlineVariant = Color(0xFF3D4357),
        ) else lightColorScheme(
            primary = accent, onPrimary = Color.White, primaryContainer = lerp(Color.White, accent, .12f),
            onPrimaryContainer = accent, secondary = accent, secondaryContainer = Color(0xFFE4E7F6),
            onSecondaryContainer = Color(0xFF26304C), surface = Color.White,
            onSurface = Color(0xFF202438), onSurfaceVariant = Color(0xFF535D72),
            surfaceVariant = Color(0xFFF0F2FA), background = Color(0xFFF4F5FB), onBackground = Color(0xFF202438),
            outline = Color(0xFF798198), outlineVariant = Color(0xFFD9DDEA),
        )
    }
    val view = LocalView.current
    SideEffect {
        val window = view.context.activity()?.window
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalAppearance provides appearance, LocalContentColor provides scheme.onBackground) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun GlassBackdrop(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val appearance = LocalAppearance.current
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (appearance.glass) {
            val spread = appearance.blur.coerceIn(0f, 1f)
            val primary = colors.primary.copy(alpha = .17f)
            val lavender = Color(0xFF9F83EB).copy(alpha = .19f)
            Spacer(Modifier.matchParentSize().blur((16 * spread).dp).drawWithCache {
                val radius = size.width * (.74f + .35f * spread)
                val left = Brush.radialGradient(listOf(primary, Color.Transparent),
                    center = Offset(0f, size.height * .12f), radius = radius.coerceAtLeast(1f))
                val right = Brush.radialGradient(listOf(lavender, Color.Transparent),
                    center = Offset(size.width * 1.01f, size.height * .38f), radius = radius.coerceAtLeast(1f))
                onDrawBehind { drawRect(left); drawRect(right) }
            })
        }
        content()
    }
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalAppearance.current.glass
    Surface(
        modifier = modifier, shape = RoundedCornerShape(23.dp),
        color = colors.surface.copy(alpha = if (glass) .78f else 1f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .84f)),
        shadowElevation = if (glass) 2.dp else 0.dp,
    ) { Column(content = content) }
}
