package com.xempastissimo.lightnovelreader.ui.screen.shelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.ShelfRow
import com.xempastissimo.lightnovelreader.ui.component.StateCrossfade
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shelf tabs.
 *
 * 「书架」 mirrors the source's own bookshelf and shows **only** what is on it. A book
 * favourited here but not (yet) accepted by the site is not a shelf item — showing it
 * would put the app and the site into disagreement, which is exactly what the previous
 * local-and-online merge did.
 *
 * 继续阅读 is unrelated to the shelf: it is reading history, and stays local.
 */
enum class ShelfTab(val label: String) {
    RECENT("继续阅读"),
    SHELF("书架"),
}

/** Which of the shelf's mutually exclusive bodies is on show. */
private enum class ShelfPhase { LOADING, ERROR, EMPTY, CONTENT }

data class ShelfUiState(
    val tab: ShelfTab = ShelfTab.RECENT,
    val entries: List<ShelfEntry> = emptyList(),
    val allEntries: List<ShelfEntry> = emptyList(),
    val loading: Boolean = false,
    val syncingOnline: Boolean = false,
    val loggedIn: Boolean = false,
    val error: String? = null,
    val requiresLogin: Boolean = false,
    val message: String? = null,
    val onlineCapacity: Int = 0,
    /** The site's own count of the account's favourites; null until the first sync. */
    val siteTotalCount: Int? = null,
    /** Multi-select mode for the 书架 tab. */
    val selecting: Boolean = false,
    val selection: Set<Int> = emptySet(),
) {
    /**
     * How many books the account holds.
     *
     * Prefers the site's own figure: the shelf is paged and grouped, so counting the rows
     * that happen to be loaded would under-report it.
     */
    val onlineCount: Int get() = siteTotalCount ?: allEntries.count { it.online }

    val shelfFull: Boolean get() = onlineCapacity > 0 && onlineCount >= onlineCapacity

    val allVisibleSelected: Boolean
        get() = entries.isNotEmpty() && selection.size == entries.size
}

class ShelfViewModel(
    private val shelfRepository: ShelfRepository,
    private val bookRepository: BookRepository,
    private val source: BookSource,
) : ViewModel() {

    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    /** Session state the shelf currently on screen was fetched with. */
    private var syncedWhileLoggedIn: Boolean? = null

    /** A sync is in flight; used to stop the resume hook starting a second one. */
    private var syncInFlight = false

    init {
        _state.update { it.copy(loggedIn = source.isLoggedIn(), onlineCapacity = source.onlineShelfCapacity) }
        viewModelScope.launch {
            shelfRepository.entries.collect { entries ->
                _state.update { current ->
                    current.copy(
                        allEntries = entries,
                        entries = filter(current.tab, entries),
                        loggedIn = source.isLoggedIn(),
                    )
                }
            }
        }
        // The shelf has nothing to show until the site's own list has been read, so the
        // first open pulls it instead of presenting an empty shelf and a refresh button.
        if (source.isLoggedIn()) syncOnlineShelf(quiet = true)
    }

    fun selectTab(tab: ShelfTab) {
        _state.update {
            it.copy(
                tab = tab,
                entries = filter(tab, it.allEntries),
                loggedIn = source.isLoggedIn(),
            )
        }
    }

    /**
     * Re-reads the shelf when the screen comes back to the foreground.
     *
     * The screen stays on the back stack while the user logs in, so returning to it is
     * observed here rather than relying on the view model being recreated — and a shelf
     * that mirrors the site must be re-read the moment there is a session to read it with.
     *
     * Keyed on the session rather than on every resume: a successful sync records the
     * session it was made with, so returning to the screen repeatedly does not re-read
     * the shelf. A failed sync leaves that record unset, so the next resume tries again —
     * which is a reasonable place to retry, and the refresh button covers the rest.
     */
    fun onResumed() {
        if (syncInFlight) return
        val loggedIn = source.isLoggedIn()
        if (loggedIn == syncedWhileLoggedIn) return
        if (loggedIn) {
            syncOnlineShelf(quiet = true)
        } else {
            syncedWhileLoggedIn = false
            _state.update { it.copy(loggedIn = false, error = null, requiresLogin = false) }
        }
    }

    fun syncOnlineShelf(quiet: Boolean = false) {
        if (!source.isLoggedIn()) {
            syncedWhileLoggedIn = false
            _state.update { it.copy(loggedIn = false, message = "请先登录，才能读取站点在线书架") }
            return
        }
        if (syncInFlight) return
        syncInFlight = true
        syncedWhileLoggedIn = true
        _state.update { it.copy(syncingOnline = true, error = null, requiresLogin = false) }
        viewModelScope.launch {
            runCatching { source.onlineShelf() }
                .onSuccess { shelf ->
                    shelfRepository.replaceOnlineEntries(shelf.entries.map { it.book })
                    _state.update {
                        it.copy(
                            syncingOnline = false,
                            onlineCapacity = shelf.capacity,
                            siteTotalCount = shelf.totalCount,
                            message = if (quiet) null else "已同步 ${shelf.entries.size} 本",
                        )
                    }
                }
                .onFailure { error ->
                    syncedWhileLoggedIn = false
                    _state.update {
                        it.copy(
                            syncingOnline = false,
                            error = error.toUserMessage(),
                            requiresLogin = error is HttpFailure.AuthRequired,
                            message = if (quiet && error is HttpFailure.AuthRequired) {
                                "请先登录，才能读取站点在线书架"
                            } else {
                                null
                            },
                        )
                    }
                }
            syncInFlight = false
        }
    }

    // ------------------------------------------------------------------ selection

    fun startSelecting() = _state.update { it.copy(selecting = true, selection = emptySet()) }

    fun stopSelecting() = _state.update { it.copy(selecting = false, selection = emptySet()) }

    fun toggleSelection(bookId: Int) = _state.update {
        it.copy(
            selection = if (bookId in it.selection) it.selection - bookId else it.selection + bookId,
        )
    }

    fun toggleSelectAll() = _state.update { current ->
        val visible = current.entries.mapTo(HashSet()) { it.book.bookId }
        current.copy(selection = if (current.selection.containsAll(visible)) emptySet() else visible)
    }

    fun removeSelected() = removeFromShelf(_state.value.selection)

    fun remove(entry: ShelfEntry) = removeFromShelf(setOf(entry.book.bookId))

    /**
     * Removes books from the shelf.
     *
     * The site's bookshelf *is* the shelf, so the change is made there and the site is
     * then re-read — the reload is what decides whether anything actually happened. Only
     * books the re-read no longer lists are dropped locally, so a removal the site
     * refused leaves the app agreeing with the account instead of quietly disagreeing
     * until the next sync.
     */
    private fun removeFromShelf(bookIds: Set<Int>) {
        if (bookIds.isEmpty()) return
        if (!source.isLoggedIn()) {
            _state.update { it.copy(message = "请先登录，才能从站点在线书架移除") }
            return
        }
        viewModelScope.launch {
            runCatching { source.removeFromOnlineShelf(bookIds) }

            val reloaded = runCatching { source.onlineShelf() }.getOrNull()
            val stillOnShelf = reloaded?.entries?.mapTo(HashSet()) { it.book.bookId }.orEmpty()
            val removed = if (reloaded == null) emptySet() else bookIds - stillOnShelf

            removed.forEach { bookId ->
                shelfRepository.remove(bookId)
                bookRepository.deleteOfflineCopy(bookId)
            }
            if (reloaded != null) {
                syncedWhileLoggedIn = true
                shelfRepository.replaceOnlineEntries(reloaded.entries.map { it.book })
            }

            _state.update {
                it.copy(
                    selecting = false,
                    selection = emptySet(),
                    onlineCapacity = reloaded?.capacity ?: it.onlineCapacity,
                    siteTotalCount = reloaded?.totalCount ?: it.siteTotalCount,
                    message = when {
                        reloaded == null -> "已发出移除请求，但书架重新同步失败，可稍后手动刷新"
                        removed.isEmpty() -> "站点没有移除任何书目，书架未变化"
                        removed.size == bookIds.size -> "已从站点在线书架移除 ${removed.size} 本"
                        else ->
                            "已移除 ${removed.size} 本，另有 ${bookIds.size - removed.size} 本未被站点移除"
                    },
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun filter(tab: ShelfTab, entries: List<ShelfEntry>): List<ShelfEntry> = when (tab) {
        ShelfTab.RECENT -> entries.filter { it.progress != null }.sortedByDescending { it.lastReadAt }
        // Only what the site's bookshelf holds, in the order the site lists it.
        ShelfTab.SHELF -> entries.filter { it.online }.sortedByDescending { it.addedAt }
    }

    companion object {
        fun factory(container: AppContainer) = AppViewModelFactory<ShelfViewModel> {
            ShelfViewModel(it.shelfRepository, it.bookRepository, it.bookSource)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenBook: (Int) -> Unit,
    onContinueReading: (Int, Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLogin: () -> Unit,
    viewModel: ShelfViewModel = viewModel(
        factory = ShelfViewModel.factory(com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRemoval by remember { mutableStateOf<ShelfEntry?>(null) }
    var menuBookId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // The shelf is the site's, so it has to be re-read when the user comes back from
    // logging in — the screen is kept on the back stack, so its view model is not
    // recreated and nothing else would notice the new session.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.tab == ShelfTab.SHELF && state.selecting) {
            TopAppBar(
                title = { Text("已选 ${state.selection.size} 本") },
                navigationIcon = {
                    IconButton(onClick = viewModel::stopSelecting) {
                        Icon(Icons.Filled.Close, contentDescription = "退出多选")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::toggleSelectAll) {
                        Text(if (state.allVisibleSelected) "取消全选" else "全选")
                    }
                    IconButton(
                        onClick = viewModel::removeSelected,
                        enabled = state.selection.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "移出书架")
                    }
                },
            )
        } else {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    if (state.tab == ShelfTab.SHELF) {
                        IconButton(onClick = viewModel::startSelecting) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "多选")
                        }
                        IconButton(onClick = { viewModel.syncOnlineShelf() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "同步站点在线书架")
                        }
                    }
                },
            )
        }

        ScrollableTabRow(
            selectedTabIndex = ShelfTab.entries.indexOf(state.tab),
            edgePadding = 12.dp,
        ) {
            ShelfTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        // How much of the site's bookshelf is in use. The site caps this, so the number
        // is shown against its limit rather than on its own.
        if (state.tab == ShelfTab.SHELF && state.onlineCapacity > 0) {
            OnlineShelfCount(
                count = state.onlineCount,
                capacity = state.onlineCapacity,
                inGroup = state.entries.size,
                full = state.shelfFull,
            )
        }

        // A shelf sync belongs to the 书架 tab only. It runs in the background when the
        // screen opens (or when the user returns from logging in), and letting it drive
        // the phase for 继续阅读 too would put a spinner over the reading history for a
        // request that has nothing to do with it.
        val syncing = state.syncingOnline && state.tab == ShelfTab.SHELF
        val phase = when {
            syncing || state.loading -> ShelfPhase.LOADING
            // A fetch was attempted and failed: say why instead of showing an
            // empty shelf, which reads as "you have no books".
            state.error != null && state.entries.isEmpty() -> ShelfPhase.ERROR
            state.entries.isEmpty() -> ShelfPhase.EMPTY
            else -> ShelfPhase.CONTENT
        }

        // Fades when the tab or the body changes, so switching shelves cross-fades
        // rather than swapping rows in place.
        StateCrossfade(
            targetState = state.tab to phase,
            label = "shelf-body",
            modifier = Modifier.weight(1f),
        ) { (tab, body) ->
            when (body) {
                ShelfPhase.LOADING -> LoadingBox(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                    label = if (tab == ShelfTab.SHELF) "正在读取站点在线书架…" else "读取本地书架…",
                )

                ShelfPhase.ERROR -> EmptyBox(
                    title = if (state.requiresLogin) "站点在线书架需要登录" else "站点在线书架读取失败",
                    hint = state.error,
                    actionLabel = "重试",
                    onAction = viewModel::syncOnlineShelf,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                )

                ShelfPhase.EMPTY -> EmptyBox(
                    title = when (tab) {
                        ShelfTab.RECENT -> "还没有阅读记录"
                        ShelfTab.SHELF -> if (state.loggedIn) "站点在线书架还是空的" else "登录后查看站点书架"
                    },
                    hint = when (tab) {
                        ShelfTab.RECENT -> "打开任意一本书开始阅读，进度会自动记录"
                        ShelfTab.SHELF -> if (state.loggedIn) {
                            "书架展示的是站点账号里的收藏。在书籍详情页点星标即可加入，" +
                                "也可以点右上角刷新重新读取"
                        } else {
                            "书架展示的是站点账号里的收藏，需要先登录才能读取"
                        }
                    },
                    actionLabel = if (tab == ShelfTab.SHELF && !state.loggedIn) "去登录" else "去搜索",
                    onAction = {
                        if (tab == ShelfTab.SHELF && !state.loggedIn) onOpenLogin() else onOpenSearch()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                )

                ShelfPhase.CONTENT -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(state.entries, key = { it.book.bookId }) { entry ->
                        val bookId = entry.book.bookId
                        val selected = bookId in state.selection
                        Box {
                            ShelfRow(
                                book = entry.book,
                                progressText = entry.progress?.let { progress ->
                                    "读到 第 ${progress.chapterIndex + 1} 章" +
                                        if (entry.book.latestChapter.isNotBlank()) {
                                            " · 最新：${entry.book.latestChapter}"
                                        } else {
                                            ""
                                        }
                                },
                                progressFraction = null,
                                offline = entry.hasOfflineCopy,
                                onClick = {
                                    if (state.selecting) {
                                        viewModel.toggleSelection(bookId)
                                    } else {
                                        val chapterId = entry.progress?.chapterId
                                        if (chapterId != null && chapterId > 0) {
                                            onContinueReading(bookId, chapterId)
                                        } else {
                                            onOpenBook(bookId)
                                        }
                                    }
                                },
                                // Long press still opens the row's menu while browsing;
                                // inside multi-select it is just another way to tick a row.
                                onLongClick = {
                                    if (state.selecting) viewModel.toggleSelection(bookId)
                                    else menuBookId = bookId
                                },
                                // Removing a book now fades the row out and lets the rows
                                // below it close the gap, instead of the list jumping.
                                modifier = Modifier.animateItem(),
                                trailing = {
                                    if (state.selecting) {
                                        Checkbox(checked = selected, onCheckedChange = null)
                                    } else {
                                        IconButton(onClick = { pendingRemoval = entry }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "移除")
                                        }
                                    }
                                },
                            )
                            if (!state.selecting) {
                                RowMenu(
                                    expanded = menuBookId == bookId,
                                    onDismiss = { menuBookId = null },
                                    onOpenDetail = {
                                        menuBookId = null
                                        onOpenBook(bookId)
                                    },
                                    onRemove = {
                                        menuBookId = null
                                        pendingRemoval = entry
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // Removing now always reaches the account's bookshelf on the site, so the tap asks
    // first and says plainly what else it takes with it.
    pendingRemoval?.let { entry ->
        RemoveBookDialog(
            title = "移除《${entry.book.title}》？",
            body = buildString {
                append("会从站点在线书架中移除，并删除本机已缓存的章节。")
                if (entry.progress != null) {
                    append("阅读进度会一并清除。")
                }
            },
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                viewModel.remove(entry)
            },
        )
    }

    SnackbarHost(hostState = snackbarHostState)
}

/** How opaque the confirmation card is; the shelf stays faintly visible through it. */
private const val CONFIRM_SURFACE_ALPHA = 0.88f

/**
 * The removal confirmation.
 *
 * Deliberately not a centred `AlertDialog`: the card is translucent and sits low on the
 * screen, so the row being asked about stays in view behind it and the question reads as
 * being about that row rather than as a screen-level interruption.
 *
 * Keeping the card low needs a full-screen dialog window, which costs the platform's own
 * tap-outside dismissal — nothing is outside a full-screen window — so the scrim handles
 * it. Both tap handlers are `pointerInput` rather than `clickable` so that neither the
 * scrim nor the card picks up a click semantic a screen reader would announce.
 */
@Composable
private fun RemoveBookDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    // Swallows taps that land on the card so they never reach the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = CONFIRM_SURFACE_ALPHA),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("移除")
                        }
                    }
                }
            }
        }
    }
}

/** How much of the site's bookshelf is in use, against the site's own cap. */
@Composable
private fun OnlineShelfCount(count: Int, capacity: Int, inGroup: Int, full: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "共 $count / $capacity 本",
                style = MaterialTheme.typography.labelMedium,
                color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The list can be shorter than the total because the site pages and groups its
            // bookshelf while this app reads only the default group. Saying so beats a list
            // that looks inexplicably truncated.
            if (inGroup != count) {
                Text(
                    text = "本组 $inGroup 本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (full) {
            Text(
                text = "已达站点上限",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The actions a shelf row offers on long press. */
@Composable
private fun RowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("查看简介") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = onOpenDetail,
        )
        DropdownMenuItem(
            text = { Text("从书架移除") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = onRemove,
        )
    }
}
