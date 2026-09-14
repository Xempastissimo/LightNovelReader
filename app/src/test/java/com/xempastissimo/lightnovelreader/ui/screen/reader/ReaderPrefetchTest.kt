package com.xempastissimo.lightnovelreader.ui.screen.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reader fetches before it is asked to.
 *
 * The rule being pinned is "the next chapter always, the previous one only from the start of
 * this chapter": fetching the next one costs the same request the turn would have cost, while
 * fetching the previous one from the middle of a chapter is a page load the site never needed
 * to serve.
 */
class ReaderPrefetchTest {

    private fun targets(
        chapterIndex: Int,
        chapterCount: Int = 10,
        atChapterStart: Boolean = false,
        cached: Set<Int> = emptySet(),
    ) = prefetchTargets(chapterIndex, chapterCount, atChapterStart) { it in cached }

    @Test
    fun `the next chapter is always worth fetching`() {
        assertEquals(listOf(4), targets(chapterIndex = 3))
    }

    @Test
    fun `the previous chapter is fetched only from the start of this one`() {
        // Mid-chapter: the next chapter only.
        assertEquals(listOf(5), targets(chapterIndex = 4, atChapterStart = false))
        // At the start: the next one, and the one behind, which is now plausibly wanted.
        assertEquals(listOf(5, 3), targets(chapterIndex = 4, atChapterStart = true))
    }

    /** The next chapter is listed before the previous one, so it is fetched first. */
    @Test
    fun `the next chapter comes first`() {
        assertEquals(5, targets(chapterIndex = 4, atChapterStart = true).first())
    }

    @Test
    fun `a chapter already on disk costs nothing`() {
        assertEquals(emptyList<Int>(), targets(chapterIndex = 3, cached = setOf(4)))
        assertEquals(
            emptyList<Int>(),
            targets(chapterIndex = 4, atChapterStart = true, cached = setOf(3, 5)),
        )
    }

    @Test
    fun `only the uncached neighbour is fetched`() {
        assertEquals(listOf(5), targets(chapterIndex = 4, atChapterStart = true, cached = setOf(3)))
    }

    @Test
    fun `the first chapter has no previous chapter`() {
        assertEquals(listOf(1), targets(chapterIndex = 0, atChapterStart = true))
    }

    @Test
    fun `the last chapter has no next chapter`() {
        assertEquals(emptyList<Int>(), targets(chapterIndex = 9, chapterCount = 10))
        assertEquals(listOf(8), targets(chapterIndex = 9, chapterCount = 10, atChapterStart = true))
        assertTrue(targets(chapterIndex = 9, chapterCount = 10).none { it == 10 })
    }

    @Test
    fun `a book with no chapters has nothing to fetch`() {
        assertEquals(emptyList<Int>(), targets(chapterIndex = 0, chapterCount = 0))
    }
}
