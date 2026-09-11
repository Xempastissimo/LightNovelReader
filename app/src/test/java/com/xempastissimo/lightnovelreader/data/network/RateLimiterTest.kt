package com.xempastissimo.lightnovelreader.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private class FakeClock {
        var now = 0L
        val sleeps = ArrayList<Long>()
    }

    private fun limiter(clock: FakeClock, interval: Long = 900L) = RateLimiter(
        minIntervalMillis = interval,
        now = { clock.now },
        sleep = { millis ->
            clock.sleeps.add(millis)
            clock.now += millis
        },
    )

    @Test
    fun `first request does not wait`() = runTest {
        val clock = FakeClock()
        limiter(clock).acquire()
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `subsequent requests are spaced by the minimum interval`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock, interval = 900L)
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
        assertEquals(listOf(900L, 900L), clock.sleeps)
    }

    @Test
    fun `no wait when enough wall clock time already elapsed`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock, interval = 900L)
        limiter.acquire()
        clock.now += 5_000L
        limiter.acquire()
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `backoff grows exponentially`() = runTest {
        val clock = FakeClock()
        val limiter = RateLimiter(
            minIntervalMillis = 0,
            baseBackoffMillis = 1_000,
            now = { clock.now },
            sleep = { clock.sleeps.add(it) },
        )
        limiter.backoff(0)
        limiter.backoff(1)
        limiter.backoff(2)
        limiter.backoff(3)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), clock.sleeps)
    }

    @Test
    fun `cooldown pauses every following request`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock, interval = 0L)
        // A sibling request is due immediately...
        limiter.acquire()
        assertTrue(clock.sleeps.isEmpty())

        // ...until the client is throttled: the source's limit is per client.
        limiter.coolDown(30_000)
        limiter.acquire()
        assertEquals(30_000L, clock.sleeps.last())
    }

    @Test
    fun `cooldown is clamped to a sane range`() = runTest {
        val clock = FakeClock()
        val limiter = limiter(clock, interval = 0L)

        limiter.coolDown(1_000)
        assertEquals(RateLimiter.MIN_COOLDOWN_MILLIS, limiter.cooldownRemainingMillis())

        limiter.coolDown(10_000_000)
        assertEquals(RateLimiter.MAX_COOLDOWN_MILLIS, limiter.cooldownRemainingMillis())
    }

    @Test
    fun `parses Retry-After seconds and dates`() {
        assertEquals(30_000L, RateLimiter.parseRetryAfter("30"))
        assertEquals(null, RateLimiter.parseRetryAfter("0"))
        assertEquals(null, RateLimiter.parseRetryAfter(null))
        assertEquals(null, RateLimiter.parseRetryAfter("not-a-number"))

        // A date in the future becomes a positive delay; a past date is ignored.
        val now = 1_700_000_000_000L
        val future = now + 60_000L
        val httpDate = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(future))
        val parsed = RateLimiter.parseRetryAfter(httpDate, now)
        assertTrue("expected a positive delay, got $parsed", (parsed ?: 0L) > 0L)
        assertEquals(null, RateLimiter.parseRetryAfter(httpDate, future + 10_000L))
    }

    @Test
    fun `retry policy covers throttling and server errors only`() {
        val limiter = RateLimiter(maxRetries = 3)
        assertTrue(limiter.shouldRetry(0, 429))
        assertTrue(limiter.shouldRetry(0, 503))
        assertTrue(limiter.shouldRetry(0, 500))
        assertFalse(limiter.shouldRetry(0, 404))
        assertFalse(limiter.shouldRetry(3, 429))
    }
}
