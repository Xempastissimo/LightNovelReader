package com.xempastissimo.lightnovelreader.ui.screen.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.component.BookCard
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
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
    ALL_VISIT("热门", RankType.ALL_VISIT),
    MONTH("本月", RankType.MONTH_VISIT),
    DAY("今日", RankType.DAY_VISIT),
    ANIME("动画化", RankType.ANIME),
    NEW("新书", RankType.POST_DATE),
    FINISHED("完结", RankType.FULL_FLAG, fullOnly = true),
}

data class DiscoverUiState(
    val tab: DiscoverTab = DiscoverTab.RECENT,
    val books: List<Book> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val requiresLogin: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && error == null && books.isEmpty()
}

/** Which of the screen's mutually exclusive bodies is on show. */
private enum class DiscoverPhase { LOADING, LOGIN, ERROR, EMPTY, CONTENT }

class DiscoverViewModel(private val repository: BookRepository) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    /** Session state the list currently on screen was fetched with. */
    private var loadedWhileLoggedIn = repository.isLoggedIn()

    init {
        load(DiscoverTab.RECENT)
    }

    fun selectTab(tab: DiscoverTab) {
        if (tab == _state.value.tab && _state.value.books.isNotEmpty()) return
        load(tab)
    }

    fun refresh() = load(_state.value.tab)

    /**
     * Called every time the screen comes back to the foreground.
     *
     * The lists (and the covers derived with them) come from pages the source
     * only serves fully to a signed-in session, so a list fetched before the user
     * logged in must not stay on screen after they return from the login flow.
     * Nothing is re-fetched while the session is unchanged, which keeps the
     * polite request pacing intact.
     */
    fun onResumed() {
        val loggedIn = repository.isLoggedIn()
        val sessionChanged = loggedIn != loadedWhileLoggedIn
        // The session can also expire server-side while the local cookie is still
        // present: then the flag did not move, but the last load needed a login.
        val retryAfterLogin = loggedIn && _state.value.requiresLogin
        loadedWhileLoggedIn = loggedIn
        if (sessionChanged || retryAfterLogin) refresh()
    }

    private fun load(tab: DiscoverTab) {
        loadedWhileLoggedIn = repository.isLoggedIn()
        _state.update { it.copy(tab = tab, loading = true, error = null, requiresLogin = false) }
        viewModelScope.launch {
            runCatching {
                when {
                    tab == DiscoverTab.RECENT -> repository.recentUpdates()
                    tab.fullOnly -> repository.catalog(fullOnly = true)
                    tab.rankType != null -> repository.rank(tab.rankType)
                    else -> emptyList()
                }
            }.onSuccess { books ->
                _state.update { it.copy(books = books, loading = false, error = null) }
            }.onFailure { error ->
                val message = error.toUserMessage()
                _state.update {
                    it.copy(
                        books = emptyList(),
                        loading = false,
                        error = message,
                        requiresLogin = error is com.xempastissimo.lightnovelreader.data.network.HttpFailure.AuthRequired,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = AppViewModelFactory<DiscoverViewModel> {
            DiscoverViewModel(it.bookRepository)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onOpenBook: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModel.factory(com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current)),
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

        ScrollableTabRow(
            selectedTabIndex = DiscoverTab.entries.indexOf(state.tab),
            edgePadding = 12.dp,
        ) {
            DiscoverTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        // Hoisted out of the branch below: a crossfade keeps the outgoing body
        // composed while it fades, so that body must not assume the state it was
        // built for is still current.
        val errorMessage = state.error
        val phase = when {
            state.loading -> DiscoverPhase.LOADING
            state.requiresLogin -> DiscoverPhase.LOGIN
            errorMessage != null -> DiscoverPhase.ERROR
            state.isEmpty -> DiscoverPhase.EMPTY
            else -> DiscoverPhase.CONTENT
        }

        // Keyed on the tab as well as the phase so tapping a tab fades the old list
        // out rather than swapping its rows in place. Each branch fills the space the
        // crossfade owns, which keeps the fade from also animating the layout height.
        StateCrossfade(
            targetState = state.tab to phase,
            label = "discover-body",
            modifier = Modifier.weight(1f),
        ) { (_, body) ->
            when (body) {
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
                    onAction = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                )

                DiscoverPhase.EMPTY -> EmptyBox(
                    title = "暂无内容",
                    hint = "书源没有返回条目",
                    actionLabel = "刷新",
                    onAction = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                )

                DiscoverPhase.CONTENT -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.books, key = { it.bookId }) { book ->
                        BookCard(
                            book = book,
                            onClick = { onOpenBook(book.bookId) },
                            // Rows glide to their new positions when a refresh changes
                            // the ranking instead of the whole list snapping.
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}
