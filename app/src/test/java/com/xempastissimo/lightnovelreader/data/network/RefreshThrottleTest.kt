package com.xempastissimo.lightnovelreader.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshThrottleTest {

    private var now = 0L

    private fun throttle(interval: Long = RefreshThrottle.DEFAULT_INTERVAL_MILLIS) =
        RefreshThrottle(intervalMillis = interval, now = { now })

    @Test
    fun `the first tap always passes`() {
        assertTrue(throttle().tryAcquire())
    }

    @Test
    fun `taps inside the window are dropped`() {
        val throttle = throttle(interval = 2_000L)
        assertTrue(throttle.tryAcquire())

        now += 1_999L
        assertFalse(throttle.tryAcquire())
    }

    @Test
    fun `the next tap passes once the window has elapsed`() {
        val throttle = throttle(interval = 2_000L)
        assertTrue(throttle.tryAcquire())

        now += 2_000L
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `mashing the button keeps the floor instead of pushing it out`() {
        val throttle = throttle(interval = 2_000L)
        assertTrue(throttle.tryAcquire())

        // A tap every half-second for ten seconds: three more requests get through,
        // one at each 2s boundary, and no tap ever postpones the deadline.
        var accepted = 0
        repeat(20) {
            now += 500L
            if (throttle.tryAcquire()) accepted++
        }

        // 500ms steps land on 2000/4000/6000/8000/10000 — five boundaries.
        assertTrue("expected the floor to be kept, got $accepted passes", accepted == 5)
    }

    @Test
    fun `the default interval is the two seconds the button promises`() {
        assertTrue(RefreshThrottle.DEFAULT_INTERVAL_MILLIS == 2_000L)
    }
}
