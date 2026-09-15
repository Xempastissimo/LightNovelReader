package com.xempastissimo.lightnovelreader.ui.screen.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.network.RefreshThrottle
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer
import com.xempastissimo.lightnovelreader.ui.component.BookCard
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadMoreIndicator
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.StaggeredEntrance
import com.xempastissimo.lightnovelreader.ui.component.StateCrossfade
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The ranking tabs the home screen exposes. */
enum class DiscoverTab(val label: String, val rankType: RankType?, val fullOnly: Boolean = false) {
    RECENT("最近更新", null),
    TODAY("今日热榜", RankType.DAY_VISIT),
    MONTH("本月热榜", RankType.MONTH_VISIT),
    FOLLOWED("最受关注", RankType.GOOD_NUM),
    ANIME("已动画化", RankType.ANIME),
    NEW("最新入库", RankType.POST_DATE),
}

/** Which of a tab's mutually exclusive bodies is on show. */
enum class DiscoverPhase { LOADING, LOGIN, ERROR, EMPTY, CONTENT }

/**
 * What one tab's body has to show.
 *
 * Kept per tab rather than only for the selected one because the pager draws the neighbouring
 * pages as well: a tab whose list was read earlier in this session is already on screen by the
 * time the finger reveals it, which is what makes the drag feel like it is moving real pages
 * rather than blanks that fill in afterwards.
 */
data class DiscoverTabState(
    /**
     * `null` is "this session never read this tab" — deliberately *not* the same as a read that
     * came back empty, because an empty ranking must not be read again (see [tabAction]).
     */
    val books: List<Book>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val requiresLogin: Boolean = false,
    /** Next page to fetch (1-based). */
    val nextPage: Int = 1,
    /** Whether more pages are available. */
    val hasMore: Boolean = true,
    /** Whether a next-page fetch is in flight. */
    val loadingMore: Boolean = false,
) {
    val phase: DiscoverPhase
        get() = when {
            loading -> DiscoverPhase.LOADING
            requiresLogin -> DiscoverPhase.LOGIN
            error != null -> DiscoverPhase.ERROR
            books.isNullOrEmpty() -> DiscoverPhase.EMPTY
            else -> DiscoverPhase.CONTENT
        }
}

data class DiscoverUiState(
    /** The tab the pager has settled on: the one whose body the rest of the screen describes. */
    val tab: DiscoverTab = DiscoverTab.RECENT,
    val tabs: Map<DiscoverTab, DiscoverTabState> = emptyMap(),
) {
    /**
     * The body for [tab].
     *
     * A tab this session knows nothing about reads as its spinner rather than as 暂无内容: the
     * pager shows the neighbour while a finger drags it in, and the read only starts once the
     * drag settles — the empty message would flash for that moment.
     */
    fun tabState(tab: DiscoverTab): DiscoverTabState = tabs[tab] ?: DiscoverTabState(loading = true)
}

class DiscoverViewModel(
    private val repository: BookRepository,
    private val refreshThrottle: RefreshThrottle,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    /** Session state the list currently on screen was fetched with. */
    private var loadedWhileLoggedIn = repository.isLoggedIn()

    /** The tabs with a read in flight right now, so one tab is never read twice over. */
    private val loadingTabs = mutableSetOf<DiscoverTab>()

    init {
        // The first open of a new process is one of the two moments the source is read.
        startLoad(DiscoverTab.RECENT)
    }

    fun selectTab(tab: DiscoverTab) {
        when (tabAction(tab, _state.value.tab, _state.value.tabs[tab]?.books)) {
            TabAction.NOTHING -> Unit
            // The list is already here — showing it is a selection, not a read.
            TabAction.SHOW_CACHED -> show(tab)
            TabAction.FETCH -> startLoad(tab)
        }
    }

    /**
     * The 刷新 button: the same request as [selectTab] on the current tab, but paced.
     *
     * A tap inside the two-second window is swallowed without a word — the list is
     * already being re-read or has just been, and an error message about tapping too
     * fast would be noise. [selectTab] itself is deliberately *not* throttled: it is a
     * different request (another ranking), and, once a tab has been read, no request at all.
     */
    fun refresh() {
        if (!refreshThrottle.tryAcquire()) return
        startLoad(_state.value.tab)
    }

    /**
     * The 重试 button on a failed body.
     *
     * Not paced, on purpose: a read that failed is not the read a previous tap started, and
     * letting a window opened by that tap swallow this one would leave the failure on screen
     * with nothing to do about it (see AGENTS.md on `RefreshThrottle`).
     */
    fun retry() = startLoad(_state.value.tab)

    /**
     * Load the next page for the current tab (infinite scroll).
     *
     * Deduplicated: a second call while a fetch is in flight is a no-op.
     */
    fun loadMore() {
        val tab = _state.value.tab
        val tabState = _state.value.tabs[tab] ?: return
        if (tabState.loadingMore || !tabState.hasMore) return
        _state.update {
            it.copy(tabs = it.tabs + (tab to tabState.copy(loadingMore = true)))
        }
        viewModelScope.launch {
            val result = runCatching { fetchPage(tab, tabState.nextPage) }
            _state.update { current ->
                val previous = current.tabState(tab)
                val failure = result.exceptionOrNull()
                val newBooks = result.getOrNull()
                val tabState = if (failure == null && newBooks != null) {
                    val merged = (previous.books.orEmpty()) + newBooks
                    previous.copy(
                        books = merged,
                        nextPage = previous.nextPage + 1,
                        hasMore = newBooks.size >= PAGE_SIZE,
                        loadingMore = false,
                    )
                } else {
                    previous.copy(loadingMore = false)
                }
                current.copy(tabs = current.tabs + (tab to tabState))
            }
        }
    }

    /**
     * Called every time the screen comes back to the foreground.
     *
     * The lists (and the covers derived with them) come from pages the source
     * only serves fully to a signed-in session, so a list fetched before the user
     * logged in must not stay on screen after they return from the login flow.
     * Nothing is re-fetched while the session is unchanged, which keeps the
     * polite request pacing intact.
     *
     * It reads directly instead of going through [refresh] because it is not a button
     * press: the session change is what decides, and a tap on 刷新 a moment before
     * signing in must not swallow the read that finally carries the session.
     */
    fun onResumed() {
        val loggedIn = repository.isLoggedIn()
        val sessionChanged = loggedIn != loadedWhileLoggedIn
        // The session can also expire server-side while the local cookie is still
        // present: then the flag did not move, but the last load needed a login.
        val retryAfterLogin = loggedIn && _state.value.tabs[_state.value.tab]?.requiresLogin == true
        loadedWhileLoggedIn = loggedIn
        if (sessionChanged || retryAfterLogin) startLoad(_state.value.tab)
    }

    /** Puts [tab] on screen with the list already held for it — no source read. */
    private fun show(tab: DiscoverTab) {
        _state.update { it.copy(tab = tab) }
    }

    private fun startLoad(tab: DiscoverTab) {
        loadedWhileLoggedIn = repository.isLoggedIn()
        val alreadyReading = tab in loadingTabs
        loadingTabs += tab
        // The tab being read owns the screen from here: it is the selected one, and it shows
        // its own spinner rather than the list it had (or the empty message it would have had)
        // a moment ago. An unread tab therefore reads as loading from the instant it is asked
        // for, which is also how a half-dragged-in page describes itself.
        _state.update {
            it.copy(
                tab = tab,
                tabs = it.tabs + (
                    tab to it.tabState(tab).copy(
                        loading = true,
                        error = null,
                        requiresLogin = false,
                        nextPage = 1,
                        hasMore = true,
                        loadingMore = false,
                    )
                    ),
            )
        }
        // A second read of the same tab would be one more page load against a site that
        // throttles, and the answer is already on its way.
        if (alreadyReading) return
        viewModelScope.launch {
            val result = runCatching { fetch(tab) }
            loadingTabs -= tab
            _state.update { current ->
                val previous = current.tabState(tab)
                val failure = result.exceptionOrNull()
                val tabState = if (failure == null) {
                    val books = result.getOrThrow()
                    previous.copy(
                        books = books,
                        loading = false,
                        error = null,
                        requiresLogin = false,
                        nextPage = if (books.isNotEmpty()) 2 else 1,
                        hasMore = books.size >= PAGE_SIZE,
                    )
                } else {
                    // A failed read leaves nothing to show later, so the tab is retried the next
                    // time it is selected rather than answering with a list that was never read.
                    previous.copy(
                        books = null,
                        loading = false,
                        error = failure.toUserMessage(),
                        requiresLogin = failure is HttpFailure.AuthRequired,
                    )
                }
                // Written for the tab it belongs to whatever the pager is showing by now: a read
                // that finished for a tab the user has since left belongs here, not on screen.
                current.copy(tabs = current.tabs + (tab to tabState))
            }
        }
    }

    private suspend fun fetch(tab: DiscoverTab): List<Book> = when {
        tab == DiscoverTab.RECENT -> repository.recentUpdates()
        tab.fullOnly -> repository.catalog(fullOnly = true)
        tab.rankType != null -> repository.rank(tab.rankType)
        else -> emptyList()
    }

    private suspend fun fetchPage(tab: DiscoverTab, page: Int): List<Book> = when {
        tab.rankType != null -> repository.rank(tab.rankType, page)
        else -> emptyList()
    }

    companion object {
        /** Page size threshold: if a page returns fewer items, no more pages follow. */
        private const val PAGE_SIZE = 20

        fun factory(container: AppContainer) = AppViewModelFactory<DiscoverViewModel> {
            DiscoverViewModel(it.bookRepository, it.refreshThrottle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onOpenBook: (Book) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModel.factory(LocalAppContainer.current)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The screen is kept on the back stack while the user logs in, so returning to
    // it is observed here instead of relying on the view model being recreated.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tabs = DiscoverTab.entries
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOf(state.tab).coerceAtLeast(0),
        pageCount = { tabs.size },
    )
    val scope = rememberCoroutineScope()

    // The finger owns the tab while it drags; the view model is told which page the pager came
    // to rest on, and only that. A drag that turns back mid-way therefore costs no read at all,
    // and a settled one reads at most the tab it landed on — nothing at all if this session has
    // read it before (see `tabAction`).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            tabs.getOrNull(page)?.let(viewModel::selectTab)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("轻小说文库") },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            },
        )

        // The pager's page rather than the view model's tab: the underline belongs on the page
        // the reader is looking at, and that changes as soon as the drag passes the half-way
        // point instead of waiting for the finger to let go.
        val currentPage = pagerState.currentPage

        ScrollableTabRow(
            selectedTabIndex = currentPage,
            edgePadding = 12.dp,
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == currentPage,
                    onClick = {
                        // The row answers the tap at once and the pager catches up, instead of
                        // leaving the old tab underlined for the length of the scroll.
                        viewModel.selectTab(tab)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(tab.label) },
                )
            }
        }

        // This pager is what makes the gesture *follow the finger*: the page under the drag
        // moves with it, and letting go either snaps back or lands on the neighbour. It also
        // replaces the old hand-rolled swipe detector — the list still scrolls vertically (the
        // pager only claims a drag once it is clearly horizontal, past touch slop), a claimed
        // drag cancels the card's own tap so sliding past a row cannot open the book under the
        // finger, and a flick moves at most one tab (`PagerDefaults`' snap distance) exactly
        // like the detector did.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            // Compose the neighbour before the finger arrives, so a drag uncovers text and
            // covers instead of a blank page — the reader pre-composes its pages for the same
            // reason.
            beyondViewportPageCount = 1,
            key = { index -> tabs[index] },
        ) { page ->
            val tab = tabs[page]
            DiscoverTabBody(
                tab = tab,
                tabState = state.tabState(tab),
                onOpenBook = onOpenBook,
                onOpenSearch = onOpenSearch,
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * One tab's body: the spinner, a login wall, a failure, an empty list, or the ranking itself.
 *
 * Pulled out of [DiscoverScreen] because the pager holds one of these per page, including the
 * neighbours.
 */
@Composable
private fun DiscoverTabBody(
    tab: DiscoverTab,
    tabState: DiscoverTabState,
    onOpenBook: (Book) -> Unit,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorMessage = tabState.error

    // Keyed on the phase alone: moving between tabs is the pager's slide now, and a crossfade
    // on top of it would fade the very page the finger is dragging.
    StateCrossfade(
        targetState = tabState.phase,
        label = "discover-body-${tab.name}",
        modifier = modifier,
    ) { phase ->
        when (phase) {
            DiscoverPhase.LOADING -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                label = "正在从书源获取…",
            )

            DiscoverPhase.LOGIN -> EmptyBox(
                title = "该榜单需要登录",
                hint = "该站点要求登录后才能浏览榜单与目录",
                actionLabel = "去搜索试试",
                onAction = onOpenSearch,
                modifier = Modifier.fillMaxSize(),
            )

            DiscoverPhase.ERROR -> EmptyBox(
                title = "加载失败",
                hint = buildString {
                    append(errorMessage)
                    // The most common cause on a fresh session is the site's browser
                    // check, which the user can clear from the login flow.
                    if (errorMessage != null &&
                        (errorMessage.contains("浏览器") || errorMessage.contains("校验"))
                    ) {
                        append(" —— 请到「设置 → 账号 → 使用浏览器登录」完成校验后返回重试。")
                    }
                },
                actionLabel = "重试",
                onAction = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
            )

            DiscoverPhase.EMPTY -> EmptyBox(
                title = "暂无内容",
                hint = "书源没有返回条目",
                actionLabel = "刷新",
                onAction = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
            )

            DiscoverPhase.CONTENT -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    tabState.books.orEmpty(),
                    key = { _, book -> book.bookId },
                ) { index, book ->
                    // Trigger next page load when reaching near the end.
                    if (index == tabState.books.orEmpty().size - 5 && tabState.hasMore && !tabState.loadingMore) {
                        LaunchedEffect(Unit) { onLoadMore() }
                    }
                    StaggeredEntrance(index = index) {
                        BookCard(
                            book = book,
                            onClick = { onOpenBook(book) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                // Loading indicator at the bottom while fetching the next page.
                if (tabState.loadingMore) {
                    item(key = "load-more") {
                        LoadMoreIndicator(
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
