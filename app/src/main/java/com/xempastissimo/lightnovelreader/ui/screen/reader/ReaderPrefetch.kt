package com.xempastissimo.lightnovelreader.ui.screen.reader

/**
 * Which neighbouring chapters are worth fetching before the reader asks for them, in fetch
 * order.
 *
 * The next chapter is always worth it: a reader who is reading chapter N is about to want N+1,
 * so fetching it now costs the *same* request it would have cost later and removes the wait from
 * the turn. Summed over a book read start to finish, the request count is unchanged — the reads
 * have only moved earlier.
 *
 * The previous chapter is not, unless the reader is at the very start of this one. Somebody on
 * page 1 of chapter N is plausibly about to swipe back; somebody on page 9 is not, and fetching
 * N-1 there is a page load the site never needed to serve.
 *
 * [isCached] is asked per chapter index so that a chapter already on disk — a downloaded book's
 * whole text, or one this session already fetched — costs nothing.
 */
internal fun prefetchTargets(
    chapterIndex: Int,
    chapterCount: Int,
    atChapterStart: Boolean,
    isCached: (index: Int) -> Boolean,
): List<Int> {
    if (chapterCount <= 0) return emptyList()
    val targets = ArrayList<Int>(2)

    val next = chapterIndex + 1
    if (next in 0 until chapterCount && !isCached(next)) targets += next

    val previous = chapterIndex - 1
    if (atChapterStart && previous in 0 until chapterCount && !isCached(previous)) {
        targets += previous
    }

    return targets
}

/**
 * How many of the neighbouring chapter's illustrations to warm.
 *
 * A 插图 chapter is one image per page, and the memory cache is keyed by URL and width, so a
 * decoded plate is reused by the page that draws it. Bounded because a chapter is a handful of
 * plates and the memory cache is a fraction of the heap, not a budget to spend.
 */
internal const val MAX_PREFETCHED_ILLUSTRATIONS = 3
