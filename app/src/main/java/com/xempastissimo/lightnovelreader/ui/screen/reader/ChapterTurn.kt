package com.xempastissimo.lightnovelreader.ui.screen.reader

import kotlin.math.abs

/** Which neighbouring chapter a finished drag is asking for. */
internal enum class ChapterTurn { NONE, FORWARD, BACKWARD }

/**
 * Decides whether a finished horizontal drag should leave the current chapter.
 *
 * `HorizontalPager` refuses to move past its first or last page, so a swipe off the end
 * of a chapter never reaches the app as a page change — the intent has to be read from
 * the gesture itself. That reading is this function, kept out of the Compose layer so the
 * rules can be tested on the JVM rather than only by hand on a device.
 *
 * Four things have to hold, and each of them exists because of a way this goes wrong:
 *
 * - the pager must **not** have moved. A drag that carried the pager to another page was
 *   a page turn; treating it as "and also change chapter" would skip a page. Note that
 *   this is measured against the page the finger went *down* on, because by the time it
 *   lifts the pager may already have advanced to the edge and look stationary;
 * - the drag has to be long enough, or a tap with a little drift would turn the chapter;
 * - it has to be mostly horizontal, or scrolling down a page taller than the screen would
 *   turn the chapter at the bottom of every scroll;
 * - and the page has to actually be at that edge, which is why an empty chapter
 *   ([pageCount] of 0) can never turn.
 */
internal fun resolveChapterTurn(
    startPage: Int,
    pageCount: Int,
    pagerMoved: Boolean,
    dragX: Float,
    dragY: Float,
    thresholdPx: Float,
): ChapterTurn {
    if (pageCount <= 0) return ChapterTurn.NONE
    if (pagerMoved) return ChapterTurn.NONE
    if (abs(dragX) < thresholdPx) return ChapterTurn.NONE
    if (abs(dragX) <= abs(dragY)) return ChapterTurn.NONE
    return when {
        // Swiping left (content moves left) asks for what comes next.
        dragX < 0 && startPage == pageCount - 1 -> ChapterTurn.FORWARD
        dragX > 0 && startPage == 0 -> ChapterTurn.BACKWARD
        else -> ChapterTurn.NONE
    }
}
