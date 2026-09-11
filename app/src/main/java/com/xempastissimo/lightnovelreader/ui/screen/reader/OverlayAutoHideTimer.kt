package com.xempastissimo.lightnovelreader.ui.screen.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Auto-hide countdown for the reader overlay (chapter title bar + previous/next
 * chapter bar).
 *
 * Tapping the page reveals the overlay and starts the countdown; a second tap (or
 * the countdown running out) hides it again. While the overlay is *pinned* — a
 * settings sheet or the chapter list is open — the countdown is suspended, because
 * controls vanishing from under an open panel reads as a glitch rather than as a
 * fade-out. Unpinning does not arm the timer by itself: the caller decides to arm a
 * fresh countdown once the panel has actually closed.
 *
 * This lives outside [ReaderViewModel] so the timing policy is unit-testable on the
 * JVM (`OverlayAutoHideTimerTest`); the Compose layer only reports taps and panel
 * changes.
 */
internal class OverlayAutoHideTimer(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val onTimeout: () -> Unit,
) {

    private var job: Job? = null

    /** True while an open panel keeps the overlay on screen. */
    var isPinned: Boolean = false
        private set

    /** True while a countdown is running. */
    val isArmed: Boolean get() = job?.isActive == true

    /** (Re)starts the countdown. A no-op while pinned. */
    fun arm() {
        cancel()
        if (isPinned) return
        job = scope.launch {
            delay(timeoutMillis)
            job = null
            onTimeout()
        }
    }

    /** Stops a running countdown without firing [onTimeout]. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Suspends the countdown, e.g. while the settings sheet is open. */
    fun pin() {
        isPinned = true
        cancel()
    }

    /** Resumes counting, but only for a countdown [arm]ed after this call. */
    fun unpin() {
        isPinned = false
    }
}
