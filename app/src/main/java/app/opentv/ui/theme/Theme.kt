/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.opentv.core.AppSettings.AccentColor

/**
 * A deliberately dark, low-chroma palette.
 *
 * This is a living-room app: it is looked at in a dark room, from three metres away, often
 * for hours. Bright surfaces and saturated accents that read well on a phone in daylight are
 * actively unpleasant on a 55" panel at night, so everything here is anchored near-black with
 * a single restrained accent used only for focus and selection.
 *
 * Only that accent is user-selectable: [AccentColor] swaps the highlight tone, while the
 * near-black surfaces, text and borders stay fixed so no choice can make the UI wash out.
 */
private data class AccentTone(
    /** Highlight on dark surfaces — the default look. */
    val bright: Color,
    /** Fill behind selected chips, badges and containers on dark surfaces. */
    val container: Color,
    /** The same accent deepened enough for white-on-accent text in light mode. */
    val deep: Color,
)

private val AccentTones = mapOf(
    AccentColor.OCEAN to AccentTone(Color(0xFF58A6FF), Color(0xFF173E77), Color(0xFF2F5FC4)),
    AccentColor.VIOLET to AccentTone(Color(0xFFB08CFF), Color(0xFF3A2A63), Color(0xFF6A45D0)),
    AccentColor.EMERALD to AccentTone(Color(0xFF4FDBA0), Color(0xFF14432F), Color(0xFF127A54)),
    AccentColor.AMBER to AccentTone(Color(0xFFFFC15E), Color(0xFF4A3410), Color(0xFF9A6A05)),
    AccentColor.RUBY to AccentTone(Color(0xFFFF7D8A), Color(0xFF4A151B), Color(0xFFC02B3A)),
    AccentColor.GRAPHITE to AccentTone(Color(0xFFB8C4D6), Color(0xFF23303F), Color(0xFF44586F)),
)

private fun darkScheme(accent: AccentTone): ColorScheme = darkColorScheme(
    primary = accent.bright,
    onPrimary = Color(0xFF0A0C14),
    primaryContainer = accent.container,
    onPrimaryContainer = Color(0xFFE6EAFF),
    secondary = Color(0xFF9AA3C0),
    background = Color(0xFF06101F),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0A172A),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF12233B),
    onSurfaceVariant = Color(0xFFB4C4D9),
    outline = Color(0xFF29415F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
)

/**
 * Light mode for anyone who wants it. By default TV boxes stay dark (a white living-room
 * screen at night is nobody's friend) — that default lives at the call site, so a user who
 * explicitly picks Light in settings gets it on any device.
 */
private fun lightScheme(accent: AccentTone): ColorScheme = lightColorScheme(
    primary = accent.deep,
    onPrimary = Color.White,
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF13141A),
    surface = Color.White,
    onSurface = Color(0xFF13141A),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF4A4F60),
    outline = Color(0xFFD3D7E2),
)

/**
 * The default dark scheme, kept for screens that force a predictable dark backdrop over custom
 * artwork instead of following the user's accent.
 */
internal val DarkScheme: ColorScheme = darkScheme(AccentTones.getValue(AccentColor.OCEAN))

/** Scaled up from the Material defaults: ten-foot viewing needs bigger text than a phone. */
private val OpenTvTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

/** Resolves the color scheme for a light/dark choice and the user's accent. */
fun openTvColorScheme(darkTheme: Boolean, accent: AccentColor): ColorScheme {
    val tone = AccentTones[accent] ?: AccentTones.getValue(AccentColor.OCEAN)
    return if (darkTheme) darkScheme(tone) else lightScheme(tone)
}

/** The accent a settings row should preview, so the swatch matches the real highlight color. */
fun accentPreviewColor(accent: AccentColor): Color =
    (AccentTones[accent] ?: AccentTones.getValue(AccentColor.OCEAN)).bright

/** Applies the application color scheme, television typography and shared Material styling. */
@Composable
fun OpenTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentColor = AccentColor.OCEAN,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = openTvColorScheme(darkTheme, accent),
        typography = OpenTvTypography,
        content = content,
    )
}
