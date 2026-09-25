package com.veeshays.pulse.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Pulse theme bridge: the 5 PC palettes become Material3 schemes.
 * - Keeps the exact PC accent/text roles (same product, mobile layout).
 * - SYSTEM/DARK/LIGHT honored; One UI dynamic color used ONLY when the user
 *   picks theme "system-tint"… default stays Pulse Aura so branding is stable.
 * - Finishes/glass arrive here as alphas/elevations, not new color systems.
 */
@Composable
fun PulseTheme(
    theme: PulseThemeId = PulseThemeId.AURA,
    mode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val ctx = LocalContext.current
    val scheme = when {
        // Genuine One UI integration: dynamic color only on explicit opt-in.
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> theme.dark.toScheme(true)
        else -> theme.light.toScheme(false)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun PulsePalette.toScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = acc, secondary = acc2, tertiary = acc3,
        background = Color(0xFF0C0C13), surface = Color(0xFF12121B),
        surfaceVariant = panel, outline = brd,
        onBackground = txt, onSurface = txt, onSurfaceVariant = tx2,
        primaryContainer = sel, onPrimaryContainer = imp,
    )
} else {
    lightColorScheme(
        primary = acc, secondary = acc2, tertiary = acc3,
        background = Color(0xFFF6F5FA), surface = Color(0xFFFFFFFF),
        surfaceVariant = panel, outline = brd,
        onBackground = txt, onSurface = txt, onSurfaceVariant = tx2,
        primaryContainer = sel, onPrimaryContainer = imp,
    )
}
