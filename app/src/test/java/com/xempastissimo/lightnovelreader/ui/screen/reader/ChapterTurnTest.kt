package com.xempastissimo.lightnovelreader.ui.screen.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules that decide whether a swipe leaves the current chapter.
 *
 * Every case here is one that produces a visibly wrong reader if it goes the other way:
 * a skipped page, a chapter turned by a scroll, a chapter turned by a tap.
 */
class ChapterTurnTest {

    private val threshold = 100f

    /** A long, purely horizontal swipe. */
    private fun swipe(distance: Float) = distance to 0f

    @Test
    fun `swiping past the last page asks for the next chapter`() {
        val (x, y) = swipe(-200f)

        assertEquals(
            ChapterTurn.FORWARD,
            resolveChapterTurn(
                startPage = 4,
                pageCount = 5,
                pagerMoved = false,
                dragX = x,
                dragY = y,
                thresholdPx = threshold,
            ),
        )
    }

    @Test
    fun `swiping back from the first page asks for the previous chapter`() {
        val (x, y) = swipe(200f)

        assertEquals(
            ChapterTurn.BACKWARD,
            resolveChapterTurn(
                startPage = 0,
                pageCount = 5,
                pagerMoved = false,
                dragX = x,
                dragY = y,
                thresholdPx = threshold,
            ),
        )
    }

    /** A single-page chapter is both edges at once, so both directions must work. */
    @Test
    fun `a one page chapter turns either way`() {
        fun turn(dragX: Float) = resolveChapterTurn(
            startPage = 0,
            pageCount = 1,
            pagerMoved = false,
            dragX = dragX,
            dragY = 0f,
            thresholdPx = threshold,
        )

        assertEquals(ChapterTurn.FORWARD, turn(-200f))
        assertEquals(ChapterTurn.BACKWARD, turn(200f))
    }

    /** Swiping the other way at an edge is simply nothing. */
    @Test
    fun `swiping into the chapter does not change it`() {
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 4,
                pageCount = 5,
                pagerMoved = false,
                dragX = 200f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 0,
                pageCount = 5,
                pagerMoved = false,
                dragX = -200f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
    }

    /**
     * The page-payload case: a drag that carried the pager from the second-to-last page
     * onto the last one ends with the pager *on* the last page. Reading the edge from the
     * final position instead of the starting one would turn the chapter as well, skipping
     * the page the user just turned to.
     */
    @Test
    fun `a drag that moved the pager is a page turn, not a chapter turn`() {
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 3,
                pageCount = 5,
                pagerMoved = true,
                dragX = -200f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
    }

    @Test
    fun `a short drag is not a turn`() {
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 4,
                pageCount = 5,
                pagerMoved = false,
                dragX = -40f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
    }

    /** Scrolling a page taller than the screen must not turn the chapter at the bottom. */
    @Test
    fun `a mostly vertical drag is a scroll, not a turn`() {
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 4,
                pageCount = 5,
                pagerMoved = false,
                dragX = -120f,
                dragY = -400f,
                thresholdPx = threshold,
            ),
        )
    }

    @Test
    fun `a chapter with no pages never turns`() {
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 0,
                pageCount = 0,
                pagerMoved = false,
                dragX = -400f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
    }

    @Test
    fun `a page in the middle turns neither way`() {
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 2,
                pageCount = 5,
                pagerMoved = false,
                dragX = -400f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
        assertEquals(
            ChapterTurn.NONE,
            resolveChapterTurn(
                startPage = 2,
                pageCount = 5,
                pagerMoved = false,
                dragX = 400f,
                dragY = 0f,
                thresholdPx = threshold,
            ),
        )
    }
}
