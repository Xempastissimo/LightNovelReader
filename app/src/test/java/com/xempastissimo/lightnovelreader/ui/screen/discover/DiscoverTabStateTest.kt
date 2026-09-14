package com.xempastissimo.lightnovelreader.ui.screen.discover

import com.xempastissimo.lightnovelreader.domain.model.Book
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which body a tab shows, and in which order the reasons win.
 *
 * The pager draws neighbouring tabs as well as the selected one, so these bodies are built for
 * tabs a finger is halfway through revealing: what an *unknown* tab says matters, because the
 * read for it has not started yet at that point.
 */
class DiscoverTabStateTest {

    private val book = Book(bookId = 1, title = "示例书名")

    /** Nothing read yet, but a drag is already uncovering the page: it must not say 暂无内容. */
    @Test
    fun `a tab this session knows nothing about reads as loading`() {
        assertEquals(
            DiscoverPhase.LOADING,
            DiscoverUiState().tabState(DiscoverTab.ALL_VISIT).phase,
        )
    }

    /** The initial tab is the one a cold start reads, and it is loading for the same reason. */
    @Test
    fun `the initial tab starts on its spinner`() {
        assertEquals(DiscoverPhase.LOADING, DiscoverUiState().tabState(DiscoverTab.RECENT).phase)
    }

    @Test
    fun `a read that came back empty is empty rather than loading`() {
        val state = DiscoverTabState(books = emptyList())

        assertEquals(DiscoverPhase.EMPTY, state.phase)
    }

    @Test
    fun `a list shows as content`() {
        assertEquals(DiscoverPhase.CONTENT, DiscoverTabState(books = listOf(book)).phase)
    }

    @Test
    fun `a read in flight shows its spinner over the list it already had`() {
        val state = DiscoverTabState(books = listOf(book), loading = true)

        assertEquals(DiscoverPhase.LOADING, state.phase)
    }

    /** A failure is worth more than the empty list it left behind, and a login wall more still. */
    @Test
    fun `failures outrank the empty list they leave`() {
        assertEquals(
            DiscoverPhase.ERROR,
            DiscoverTabState(error = "网络错误").phase,
        )
        assertEquals(
            DiscoverPhase.LOGIN,
            DiscoverTabState(error = "需要登录", requiresLogin = true).phase,
        )
    }
}
