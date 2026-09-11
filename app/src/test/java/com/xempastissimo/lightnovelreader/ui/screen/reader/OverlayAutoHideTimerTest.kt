package com.xempastissimo.lightnovelreader.ui.screen.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the reader overlay policy: the top title bar and the bottom
 * previous/next chapter bar hide themselves 5 seconds after the last tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayAutoHideTimerTest {

    private val timeout = ReaderViewModel.CONTROLS_AUTO_HIDE_MILLIS

    @Test
    fun `hides the overlay five seconds after the last tap`() = runTest {
        var hidden = 0
        // Built with a literal so the requirement itself is under test, not just
        // the constant the production code happens to pass in.
        val timer = OverlayAutoHideTimer(backgroundScope, 5_000L) { hidden++ }

        timer.arm()
        assertTrue(timer.isArmed)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals("must stay visible until the full timeout", 0, hidden)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, hidden)
        assertFalse(timer.isArmed)
    }

    @Test
    fun `a second tap restarts the countdown instead of stacking one`() = runTest {
        var hidden = 0
        val timer = OverlayAutoHideTimer(backgroundScope, timeout) { hidden++ }

        timer.arm()
        advanceTimeBy(timeout - 1_000)
        timer.arm()
        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals("the restarted countdown has not elapsed yet", 0, hidden)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("exactly one hide, not one per tap", 1, hidden)
    }

    @Test
    fun `cancelling keeps the overlay up`() = runTest {
        var hidden = 0
        val timer = OverlayAutoHideTimer(backgroundScope, timeout) { hidden++ }

        timer.arm()
        timer.cancel()
        advanceTimeBy(timeout * 2)
        runCurrent()
        assertEquals(0, hidden)
        assertFalse(timer.isArmed)
    }

    @Test
    fun `an open panel keeps the overlay visible`() = runTest {
        var hidden = 0
        val timer = OverlayAutoHideTimer(backgroundScope, timeout) { hidden++ }

        timer.arm()
        timer.pin()
        assertTrue(timer.isPinned)
        assertFalse("pinning stops the countdown", timer.isArmed)

        // Arming while pinned is a no-op: the panel is still open.
        timer.arm()
        advanceTimeBy(timeout * 3)
        runCurrent()
        assertEquals(0, hidden)

        timer.unpin()
        timer.arm()
        advanceTimeBy(timeout)
        runCurrent()
        assertEquals("the countdown restarts once the panel closes", 1, hidden)
    }
}
