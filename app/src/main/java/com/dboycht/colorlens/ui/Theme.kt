package com.dboycht.colorlens.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

/**
 * Colours chosen for a dark, high-contrast "instrument" look: the sampled colour
 * itself must be the brightest thing on screen, so everything around it stays
 * desaturated and dark.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF53E1C0),
    onPrimary = Color(0xFF04201A),
    primaryContainer = Color(0xFF10453A),
    onPrimaryContainer = Color(0xFFB9F5E6),
    secondary = Color(0xFFFFD166),
    onSecondary = Color(0xFF2A1D00),
    tertiary = Color(0xFF8AB4FF),
    background = Color(0xFF0F1218),
    onBackground = Color(0xFFE7EAF2),
    surface = Color(0xFF171B24),
    onSurface = Color(0xFFE7EAF2),
    surfaceVariant = Color(0xFF262C38),
    onSurfaceVariant = Color(0xFFBFC6D6),
    outline = Color(0xFF8A93A6),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0A0A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF6D5200),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF2A56A8),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF12151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12151C),
    surfaceVariant = Color(0xFFE4E7EE),
    onSurfaceVariant = Color(0xFF444B5A),
    outline = Color(0xFF6C7383),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

/**
 * Pure black and pure white, with the accent pushed to maximum separation.
 *
 * This exists because the people who need it are the ones who cannot rely on
 * subtle contrast: an ordinary Material scheme very quickly becomes unreadable for
 * someone whose contrast sensitivity is reduced (which includes many people with
 * colour-vision deficiency, and everyone with 全色盲).
 */
private val HighContrastDarkScheme = darkColorScheme(
    primary = Color(0xFF00FFC8),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF004D3D),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFE066),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF9EC5FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFF2F2F2),
    outline = Color(0xFFFFFFFF),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF000000),
)

private val HighContrastLightScheme = lightColorScheme(
    primary = Color(0xFF004D40),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCEEE7),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF4A3600),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF00337A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF000000),
    outline = Color(0xFF000000),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun ColorLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    largeText: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        highContrast && darkTheme -> HighContrastDarkScheme
        highContrast -> HighContrastLightScheme
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // 大字号 is implemented by scaling the font scale rather than by overriding
    // every type style: text grows, paddings and the photo do not, so the layout
    // stays intact instead of overflowing off-screen.
    val density = LocalDensity.current
    val effectiveDensity = if (largeText) {
        Density(density.density, density.fontScale * LARGE_TEXT_SCALE)
    } else {
        density
    }

    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 1.3× still fits a 5-column grid of labels on a 360 dp phone. */
const val LARGE_TEXT_SCALE = 1.3f

/** Convenience for the rare case where a raw ARGB int must reach the framework. */
fun Color.toArgbInt(): Int = this.toArgb()
