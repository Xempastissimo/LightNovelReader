package com.xempastissimo.lightnovelreader.data.network

import kotlinx.coroutines.delay

/**
 * Global request pacing for a book source.
 *
 * Scraping a site politely is a design requirement, not a nicety: the source
 * answers 403/429 when hammered, and it explicitly forbids bulk collection.
 * Every request therefore goes through a single serial gate with a minimum
 * interval, and failures back off exponentially.
 *
 * Two distinct throttles are modelled, because the source uses both:
 * - a per-request minimum interval ([minIntervalMillis]), and
 * - a **cooldown** the whole client observes after a `429`. The source's limit is
 *   per-IP and per-window, so backing off a single request is not enough —
 *   siblings would keep arriving and keep the limit tripped.
 */
class RateLimiter(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseBackoffMillis: Long = DEFAULT_BASE_BACKOFF_MILLIS,
    private val defaultCooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    private val lock = Any()
    private var nextAllowedAt = 0L
    private var coolingDownUntil = 0L

    /** Suspends until the next request may be issued. */
    suspend fun acquire() {
        val waitFor = synchronized(lock) {
            val current = now()
            val scheduled = maxOf(current, nextAllowedAt, coolingDownUntil)
            nextAllowedAt = scheduled + minIntervalMillis
            scheduled - current
        }
        if (waitFor > 0) sleep(waitFor)
    }

    /** Exponential backoff for attempt `index` (0-based). */
    suspend fun backoff(attempt: Int) {
        if (attempt <= 0) return
        val multiplier = 1L shl (attempt - 1).coerceAtMost(5)
        sleep(baseBackoffMillis * multiplier)
    }

    /**
     * Marks the whole client as throttled after a `429`.
     *
     * [retryAfterMillis] comes from the response's `Retry-After` header when the
     * server sends one; otherwise a conservative default is used.
     */
    fun coolDown(retryAfterMillis: Long? = null) {
        val duration = (retryAfterMillis ?: defaultCooldownMillis).coerceIn(
            MIN_COOLDOWN_MILLIS,
            MAX_COOLDOWN_MILLIS,
        )
        synchronized(lock) {
            coolingDownUntil = maxOf(coolingDownUntil, now() + duration)
        }
    }

    /** Remaining cooldown, exposed for tests and diagnostics. */
    fun cooldownRemainingMillis(): Long = synchronized(lock) {
        (coolingDownUntil - now()).coerceAtLeast(0L)
    }

    fun shouldRetry(attempt: Int, statusCode: Int): Boolean =
        attempt < maxRetries && (statusCode == 429 || statusCode == 503 || statusCode == 502 || statusCode >= 500)

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS = 900L
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_BASE_BACKOFF_MILLIS = 1500L

        /** Used when the source does not send `Retry-After`. */
        const val DEFAULT_COOLDOWN_MILLIS = 30_000L
        const val MIN_COOLDOWN_MILLIS = 5_000L
        const val MAX_COOLDOWN_MILLIS = 300_000L

        /**
         * Parses `Retry-After`: either a number of seconds or an HTTP date.
         * Returns null when the header is absent or unparseable.
         */
        fun parseRetryAfter(raw: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            trimmed.toLongOrNull()?.let { seconds ->
                if (seconds <= 0) return null
                return seconds * 1000L
            }
            val date = CookieStore.parseHttpDate(trimmed)
            if (date <= 0L) return null
            val delta = date - nowMillis
            return if (delta > 0) delta else null
        }
    }
}
