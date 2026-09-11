package com.xempastissimo.lightnovelreader.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette: warm ink on paper, chosen so the app reads as a book rather
// than a generic Material demo.

// Light
val InkBlue = Color(0xFF2F4858)
val InkBlueLight = Color(0xFF446B80)
val PaperWarm = Color(0xFFFBF7F0)
val PaperSurface = Color(0xFFFFFDF8)
val PaperSurfaceVariant = Color(0xFFEDE4D6)
val AccentAmber = Color(0xFFB4762A)
val AccentAmberLight = Color(0xFFE0A458)
val OutlineWarm = Color(0xFF7C7161)
val OnPaper = Color(0xFF1F1B16)
val OnPaperVariant = Color(0xFF4F4639)

// Dark
val InkBlueDark = Color(0xFFA8CBE0)
val AccentAmberDark = Color(0xFFF0C48A)
val NightBackground = Color(0xFF14110E)
val NightSurface = Color(0xFF1D1A16)
val NightSurfaceVariant = Color(0xFF2C2822)
val OnNight = Color(0xFFEDE5D8)
val OnNightVariant = Color(0xFFC9BFAD)

// The reader's floating bars used to be a fixed blue here. They are now the user's
// choice, so their colours live in ReaderPalette.kt and their default is stored as
// the HSV of that same blue in ReaderSettings.DEFAULT_BAR_*.

// Reader page colours (independent of the Material scheme)
val ReaderPaper = Color(0xFFF6EFE3)
val ReaderPaperText = Color(0xFF2B2620)
val ReaderWhite = Color(0xFFFCFCFA)
val ReaderWhiteText = Color(0xFF1A1A1A)
val ReaderGreen = Color(0xFFCCE8CF)
val ReaderGreenText = Color(0xFF1F3222)
val ReaderNight = Color(0xFF121212)
val ReaderNightText = Color(0xFFB9B4AC)
