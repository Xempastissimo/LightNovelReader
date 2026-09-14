package com.xempastissimo.lightnovelreader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xempastissimo.lightnovelreader.data.repo.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * OLED pure black on the reading page.
 *
 * What matters is that the switch reaches exactly one theme and exactly one colour: the night
 * page's background, and `#000000` rather than a near-black. The saving comes from the panel's
 * pixels being off, which only happens at zero — `#121212` draws power much like any other dark
 * grey — so "nearly black" would be a setting that looks right and saves nothing.
 */
class ReaderPaletteTest {

    @Test
    fun `the night page is pure black only while OLED mode is on`() {
        assertEquals(ReaderNight, readerPalette(ReaderTheme.DARK).background)
        assertEquals(Color.Black, readerPalette(ReaderTheme.DARK, oledBlack = true).background)
    }

    /** The other three themes are light pages; painting them black would hide their own ink. */
    @Test
    fun `OLED mode never touches the light pages`() {
        for (theme in listOf(ReaderTheme.PAPER, ReaderTheme.LIGHT, ReaderTheme.GREEN)) {
            assertEquals(
                "$theme must not be repainted",
                readerPalette(theme).background,
                readerPalette(theme, oledBlack = true).background,
            )
        }
    }

    @Test
    fun `OLED mode does not change the ink`() {
        assertEquals(
            readerPalette(ReaderTheme.DARK).text,
            readerPalette(ReaderTheme.DARK, oledBlack = true).text,
        )
    }

    /** The claim the switch makes: the night background is emitting nothing. */
    @Test
    fun `the OLED night background measures as zero luminance`() {
        assertEquals(0f, readerPalette(ReaderTheme.DARK, oledBlack = true).background.luminance(), 0.0001f)
        assertNotEquals(0f, readerPalette(ReaderTheme.DARK).background.luminance(), 0.0001f)
    }

    /**
     * The bars follow the page when the user asks them to, so they have to follow it into OLED
     * mode as well — otherwise a pure black page keeps a `#121212` bar above and below it.
     */
    @Test
    fun `bars that follow the page follow it to pure black`() {
        val settings = com.xempastissimo.lightnovelreader.data.repo.ReaderSettings(
            theme = ReaderTheme.DARK,
            barFollowsTheme = true,
        )

        assertEquals(
            ReaderNight,
            readerBarPalette(settings, readerPalette(ReaderTheme.DARK)).container,
        )
        assertEquals(
            Color.Black,
            readerBarPalette(settings, readerPalette(ReaderTheme.DARK, oledBlack = true)).container,
        )
    }
}
