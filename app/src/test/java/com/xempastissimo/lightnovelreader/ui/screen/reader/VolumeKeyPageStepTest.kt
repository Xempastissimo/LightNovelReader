package com.xempastissimo.lightnovelreader.ui.screen.reader

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which way each volume key turns the page.
 *
 * Both mappings are locked here rather than only the new one: `反转音量翻页` is a *swap*, and a
 * swap that silently became "both keys go forward" would look like the reader ignoring the
 * back key rather than like a wrong setting.
 */
class VolumeKeyPageStepTest {

    /** Asserts the direction for one key, taking the nullable result so a null fails loudly. */
    private fun assertStep(expected: Int, key: Key, inverted: Boolean) {
        assertEquals(expected, volumeKeyPageStep(key = key, inverted = inverted))
    }

    @Test
    fun `by default volume down goes forward and volume up goes back`() {
        assertStep(1, Key.VolumeDown, inverted = false)
        assertStep(-1, Key.VolumeUp, inverted = false)
    }

    @Test
    fun `inverting swaps the two keys`() {
        assertStep(-1, Key.VolumeDown, inverted = true)
        assertStep(1, Key.VolumeUp, inverted = true)
    }

    /** Inverting has to change both keys, or the two would fight over the same direction. */
    @Test
    fun `neither mapping leaves both keys pointing the same way`() {
        assertNotEquals(
            volumeKeyPageStep(Key.VolumeDown, inverted = true),
            volumeKeyPageStep(Key.VolumeUp, inverted = true),
        )
        assertNotEquals(
            volumeKeyPageStep(Key.VolumeDown, inverted = false),
            volumeKeyPageStep(Key.VolumeUp, inverted = false),
        )
    }

    /** Any other key is not ours: the handler falls through to whatever owns it. */
    @Test
    fun `other keys are not a page turn`() {
        assertNull(volumeKeyPageStep(Key.VolumeMute, inverted = false))
        assertNull(volumeKeyPageStep(Key.VolumeMute, inverted = true))
        assertNull(volumeKeyPageStep(Key.Spacebar, inverted = false))
    }
}
