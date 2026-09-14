package com.xempastissimo.lightnovelreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview

private val DarkColorScheme = darkColorScheme(
    primary = InkBlueDark,
    onPrimary = Color(0xFF10222C),
    primaryContainer = Color(0xFF2B4A5B),
    onPrimaryContainer = Color(0xFFD3E8F4),
    secondary = OnNightVariant,
    onSecondary = Color(0xFF2A241C),
    secondaryContainer = NightSurfaceVariant,
    onSecondaryContainer = OnNight,
    tertiary = AccentAmberDark,
    onTertiary = Color(0xFF3A2A10),
    background = NightBackground,
    onBackground = OnNight,
    surface = NightSurface,
    onSurface = OnNight,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = OnNightVariant,
    outline = Color(0xFF8A8071),
    outlineVariant = Color(0xFF3A352D),
    error = Color(0xFFFFB4AB),
)

private val LightColorScheme = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E4EE),
    onPrimaryContainer = Color(0xFF10222C),
    secondary = InkBlueLight,
    onSecondary = Color.White,
    secondaryContainer = PaperSurfaceVariant,
    onSecondaryContainer = OnPaperVariant,
    tertiary = AccentAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E2C4),
    onTertiaryContainer = Color(0xFF3A2708),
    background = PaperWarm,
    onBackground = OnPaper,
    surface = PaperSurface,
    onSurface = OnPaper,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = OnPaperVariant,
    outline = OutlineWarm,
    outlineVariant = Color(0xFFDBD0BE),
    error = Color(0xFFB3261E),
)

/**
 * App theme.
 *
 * Dynamic colour is opt-in (see 设置 → 外观) because the reader's paper palette is
 * part of the reading experience and a random accent colour fights it.
 *
 * [oledBlack] replaces the dark scheme's large surfaces with `#000000` — see
 * [withOledBlack]. It is ignored on the light scheme, where pure black would be a
 * background with dark text on it.
 */
@Composable
fun LightNovelReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    oledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && oledBlack) dynamic.withOledBlack() else dynamic
        }

        darkTheme && oledBlack -> DarkColorScheme.withOledBlack()
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

/**
 * The same scheme with its page-level surfaces at `#000000`.
 *
 * Only `background` and `surface` change. Those are the two that cover whole screens — a
 * `Scaffold`'s container and a standalone `Surface` — which is where the panel's power actually
 * goes; and they are the two whose replacement cannot cost anybody legibility, because the
 * container roles that dropdowns, sheets and the navigation bar paint with are left exactly as
 * they were. Painting *those* black as well would put a black menu on a black page, which on an
 * OLED panel is a menu with no visible edge: there is no shadow to see when the surface behind it
 * is already off.
 *
 * Written as an extension rather than a second `darkColorScheme(...)` call so that it applies to
 * the dynamic scheme too — the wallpaper's idea of "dark" is a dark grey, and a user asking for
 * pure black has asked for it regardless of where the rest of the colours came from.
 */
fun ColorScheme.withOledBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
)

@Preview(showBackground = true, name = "Light Theme")
@Composable
private fun LightThemePreview() {
    LightNovelReaderTheme(darkTheme = false) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("轻小说阅读器", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun DarkThemePreview() {
    LightNovelReaderTheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("轻小说阅读器", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** The night theme at `#000000`, which is what the OLED switch is for. */
@Preview(showBackground = true, name = "Dark Theme · OLED")
@Composable
private fun OledThemePreview() {
    LightNovelReaderTheme(darkTheme = true, oledBlack = true) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("轻小说阅读器", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
