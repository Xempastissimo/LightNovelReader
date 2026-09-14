package com.xempastissimo.lightnovelreader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OLED pure black on the app's own surfaces.
 *
 * The switch has to reach the two roles that cover whole screens and nothing else: the container
 * roles are what dropdowns, sheets and the navigation bar paint with, and those have to stay
 * lifted — on a black page the shadow that would separate them is invisible, so a black menu would
 * be a menu with no edge at all.
 */
class ThemeOledTest {

    @Test
    fun `the OLED scheme turns its page surfaces off`() {
        val scheme = darkColorScheme().withOledBlack()

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }

    @Test
    fun `the ink is left alone`() {
        val base = darkColorScheme()

        val oled = base.withOledBlack()

        assertEquals(base.onBackground, oled.onBackground)
        assertEquals(base.onSurface, oled.onSurface)
        assertEquals(base.onSurfaceVariant, oled.onSurfaceVariant)
    }

    /** Containers keep their lift: a black menu on a black page has no visible edge. */
    @Test
    fun `the container roles keep their lift so lifted surfaces stay visible`() {
        val base = darkColorScheme()

        val oled = base.withOledBlack()

        assertEquals(base.surfaceContainerLowest, oled.surfaceContainerLowest)
        assertEquals(base.surfaceContainerLow, oled.surfaceContainerLow)
        assertEquals(base.surfaceContainer, oled.surfaceContainer)
        assertEquals(base.surfaceContainerHigh, oled.surfaceContainerHigh)
        assertEquals(base.surfaceContainerHighest, oled.surfaceContainerHighest)
    }

    /** The accents are what makes the app look like itself; OLED is only about the background. */
    @Test
    fun `the accent colours are untouched`() {
        val base = darkColorScheme()

        val oled = base.withOledBlack()

        assertEquals(base.primary, oled.primary)
        assertEquals(base.secondaryContainer, oled.secondaryContainer)
        assertEquals(base.outline, oled.outline)
        assertEquals(base.error, oled.error)
    }

    /** The app's own dark scheme is what ships, so OLED has to work on it and not just on a default. */
    @Test
    fun `the app's dark scheme turns black too`() {
        val scheme = darkColorScheme(
            background = NightBackground,
            surface = NightSurface,
        ).withOledBlack()

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }
}
