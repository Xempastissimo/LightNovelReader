package com.xempastissimo.lightnovelreader.ui.screen.discover

import com.xempastissimo.lightnovelreader.domain.model.Book
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules that decide whether selecting a tab costs a page load.
 *
 * This is the whole reason the view model keeps its lists: a discovery list is shown again
 * as-is, and only a cold start or the 刷新 button reads the source.
 */
class DiscoverTabsTest {

    private val book = Book(bookId = 1, title = "示例书名")

    @Test
    fun `reselecting the tab already on screen does not read the source again`() {
        assertEquals(
            TabAction.NOTHING,
            tabAction(DiscoverTab.RECENT, DiscoverTab.RECENT, listOf(book)),
        )
    }

    @Test
    fun `a tab already read is shown without a request`() {
        assertEquals(
            TabAction.SHOW_CACHED,
            tabAction(DiscoverTab.ALL_VISIT, DiscoverTab.RECENT, listOf(book)),
        )
    }

    @Test
    fun `a tab never read is fetched`() {
        assertEquals(
            TabAction.FETCH,
            tabAction(DiscoverTab.ALL_VISIT, DiscoverTab.RECENT, null),
        )
    }

    /** A failed read leaves no list behind, so selecting that tab again is a retry. */
    @Test
    fun `a tab whose read failed is fetched again`() {
        assertEquals(
            TabAction.FETCH,
            tabAction(DiscoverTab.RECENT, DiscoverTab.RECENT, null),
        )
    }

    /**
     * A ranking that came back empty is a *result*, not a missing one: showing it again must
     * not cost a second read, or a genuinely empty tab would re-request on every visit.
     */
    @Test
    fun `a tab that came back empty is not read again`() {
        assertEquals(
            TabAction.NOTHING,
            tabAction(DiscoverTab.RECENT, DiscoverTab.RECENT, emptyList()),
        )
        assertEquals(
            TabAction.SHOW_CACHED,
            tabAction(DiscoverTab.ALL_VISIT, DiscoverTab.RECENT, emptyList()),
        )
    }
}
