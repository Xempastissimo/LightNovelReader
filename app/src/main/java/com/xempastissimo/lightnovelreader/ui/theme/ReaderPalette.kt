package com.xempastissimo.lightnovelreader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xempastissimo.lightnovelreader.data.repo.ReaderSettings
import com.xempastissimo.lightnovelreader.data.repo.ReaderTheme

/** The reader page's own colours; deliberately independent of the Material scheme. */
data class ReaderPalette(val background: Color, val text: Color)

fun readerPalette(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.PAPER -> ReaderPalette(ReaderPaper, ReaderPaperText)
    ReaderTheme.LIGHT -> ReaderPalette(ReaderWhite, ReaderWhiteText)
    ReaderTheme.GREEN -> ReaderPalette(ReaderGreen, ReaderGreenText)
    ReaderTheme.DARK -> ReaderPalette(ReaderNight, ReaderNightText)
}

/**
 * The reader's floating bars: the chapter title bar and the previous/next chapter bar.
 *
 * [content] is derived rather than stored, because the bars are the one surface the
 * user can paint any colour they like. Asking them to also pick readable ink would be
 * a second thing to get wrong, and the wrong answer is invisible text.
 */
data class ReaderBarPalette(val container: Color, val content: Color)

/** Ink for bars too dark to carry dark text. */
private val BarInkLight = Color(0xFFF3F7FF)

/** Ink for bars light enough to need it. */
private val BarInkDark = Color(0xFF14181C)

/**
 * `true` when [this] is light enough that dark ink reads better on it.
 *
 * Also used for the system status/navigation bar glyphs, which sit on the reader bars
 * while the overlay is up and on the page itself while it is hidden — so whichever
 * surface is underneath has to be able to answer this question.
 */
fun Color.prefersDarkInk(): Boolean = luminance() > 0.45f

/** Ink that stays readable on [container]. */
fun readerBarInk(container: Color): Color =
    if (container.prefersDarkInk()) BarInkDark else BarInkLight

/** The bar colour the user mixed themselves. */
fun customReaderBar(hue: Float, saturation: Float, value: Float): ReaderBarPalette {
    val container = Color.hsv(
        hue = hue.coerceIn(0f, 360f),
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f),
    )
    return ReaderBarPalette(container = container, content = readerBarInk(container))
}

/**
 * Resolves the bars from the user's choice: either the reading theme's own paper and
 * ink — the bars then blend into the page rather than floating over it — or the colour
 * they mixed themselves.
 */
fun readerBarPalette(settings: ReaderSettings, page: ReaderPalette): ReaderBarPalette =
    if (settings.barFollowsTheme) {
        ReaderBarPalette(container = page.background, content = page.text)
    } else {
        customReaderBar(settings.barHue, settings.barSaturation, settings.barValue)
    }
