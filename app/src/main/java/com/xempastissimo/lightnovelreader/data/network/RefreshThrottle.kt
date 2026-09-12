package com.xempastissimo.lightnovelreader.data.network

/**
 * Pace limiter for the screens' 刷新 buttons.
 *
 * Not to be confused with [RateLimiter]: that one paces *every* request the source
 * makes and suspends until the request is allowed. This one paces one user action —
 * repeatedly tapping 刷新 — and answers immediately, because the tap has to be
 * swallowed silently rather than queued or reported. A second request fired within
 * the window would only re-read the same page and, on this source, would count
 * towards the same per-client limit the polite pacing exists to stay under.
 *
 * A held window rather than a leading-edge debounce: the click that lands while the
 * window is open is dropped rather than postponing the deadline, so a user mashing
 * the button over ten seconds gets a request every two seconds instead of none.
 *
 * The clock is injected so the policy is unit-testable on the JVM
 * (`RefreshThrottleTest`); [SystemClock.elapsedRealtime] is the production default
 * because it cannot jump the way wall-clock time can.
 */
class RefreshThrottle(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    private var nextAllowedAt = Long.MIN_VALUE

    /**
     * True when the action may run now, advancing the window to
     * `now + intervalMillis`; false when it must be ignored.
     */
    fun tryAcquire(): Boolean {
        val current = now()
        if (current < nextAllowedAt) return false
        nextAllowedAt = current + intervalMillis
        return true
    }

    companion object {
        /** The refresh button's floor: one request per this many milliseconds. */
        const val DEFAULT_INTERVAL_MILLIS = 2_000L
    }
}
