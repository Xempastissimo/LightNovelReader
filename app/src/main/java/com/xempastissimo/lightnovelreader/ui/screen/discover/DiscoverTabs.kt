package com.xempastissimo.lightnovelreader.ui.screen.discover

import com.xempastissimo.lightnovelreader.domain.model.Book

/** What selecting a tab should do, given what this session already holds. */
internal enum class TabAction {
    /** The tab is already on screen with its list: nothing to do and nothing to read. */
    NOTHING,

    /** This tab's list was read earlier in this session; show it without asking the source. */
    SHOW_CACHED,

    /** Nothing is known about this tab yet, so it has to be read. */
    FETCH,
}

/**
 * Whether selecting [tab] needs the source at all.
 *
 * The discovery lists change when the site feels like it, not when the user looks at them, so
 * a list that has already been read is shown again as-is: switching tabs, coming back from a
 * book, or returning to the foreground must not cost a page load. Only a cold start (nothing
 * is in memory because the process was gone) and the 刷新 button re-read a tab — which is what
 * this function, together with the view model's own per-tab lists, encodes.
 *
 * A failed read leaves no list behind, so re-selecting that tab lands on [FETCH] and tries
 * again: the tab that could not be read is the one tab that *should* be retried.
 */
internal fun tabAction(tab: DiscoverTab, current: DiscoverTab, cached: List<Book>?): TabAction = when {
    tab == current && cached != null -> TabAction.NOTHING
    cached != null -> TabAction.SHOW_CACHED
    else -> TabAction.FETCH
}
