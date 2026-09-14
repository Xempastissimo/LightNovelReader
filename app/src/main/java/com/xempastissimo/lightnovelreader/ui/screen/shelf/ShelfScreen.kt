package com.xempastissimo.lightnovelreader.ui.screen.shelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import com.xempastissimo.lightnovelreader.data.repo.BookmarkStore
import com.xempastissimo.lightnovelreader.data.repo.DownloadedBook
import com.xempastissimo.lightnovelreader.data.repo.OfflineBook
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.Job
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
 *
 * 已缓存 answers a third, equally local question — what can be read with the network off —
 * and is built from the chapter files on disk rather than from either list above, so a
 * book cached but never favourited still appears.
 *
 * 已下载 answers a fourth: which books were downloaded **whole**, as the site's own pack.
 * Those rows come from the pack records, and a downloaded book is deliberately not listed
 * under 已缓存 as well — see `cachedRows` in `ShelfFilters.kt`.
 */
enum class ShelfTab(val label: String) {
    RECENT("继续阅读"),
    SHELF("书架"),
    CACHED("已缓存"),
    DOWNLOADED("已下载"),
}

data class ShelfUiState(
    val tab: ShelfTab = ShelfTab.RECENT,
    val entries: List<ShelfEntry> = emptyList(),
    val allEntries: List<ShelfEntry> = emptyList(),
    /**
     * What each book has on disk, keyed by book id.
     *
     * Read from the cache directory rather than from `ShelfEntry.cachedChapterIds`: the
     * record and the files can drift apart (see `ChapterCache.offlineBooks`), and the
     * 已缓存 tab has to describe the files.
     */
    val offline: Map<Int, OfflineBook> = emptyMap(),
    /**
     * Whole-book packs on this device, keyed by book id.
     *
     * Also read from disk — `filesDir/packs/{bookId}/index.json` — for the same reason: the
     * files are what the 已下载 tab describes, and a pack can outlive the shelf row it was
     * downloaded from.
     */
    val downloads: Map<Int, DownloadedBook> = emptyMap(),
    /**
     * How many local bookmarks each book has.
     *
     * The 已下载 tab's delete has to say that the bookmarks go with the download, so the count has
     * to be on screen before the question is asked rather than looked up after it is answered.
     */
    val bookmarkCounts: Map<Int, Int> = emptyMap(),
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

    /** Total space the offline copies take, for the 已缓存 tab's header. */
    val offlineBytes: Long get() = offline.values.sumOf { it.sizeBytes }

    /** Total space the downloaded packs take, for the 已下载 tab's header. */
    val downloadBytes: Long get() = downloads.values.sumOf { it.bytes }

    val allVisibleSelected: Boolean
        get() = entries.isNotEmpty() && selection.size == entries.size
}

class ShelfViewModel(
    private val shelfRepository: ShelfRepository,
    private val bookRepository: BookRepository,
    private val bookmarkStore: BookmarkStore,
    private val source: BookSource,
    private val refreshThrottle: RefreshThrottle,
) : ViewModel() {

    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    /** Session state the shelf currently on screen was fetched with. */
    private var syncedWhileLoggedIn: Boolean? = null

    /** A sync is in flight; used to stop the resume hook starting a second one. */
    private var syncInFlight = false

    /** The metadata backfill pass, while one is running. */
    private var metadataBackfillJob: Job? = null

    /**
     * Books this session has already asked the source about.
     *
     * In memory on purpose: [Book.needsFullerMetadata] is what decides a book is unknown, and
     * a book the site simply has no description for would keep satisfying it. Persisting a
     * "asked and got nothing" marker would mean another field in `shelf.json` for a case one
     * bounded retry per app start already covers.
     */
    private val metadataAttempted = HashSet<Int>()

    init {
        _state.update { it.copy(loggedIn = source.isLoggedIn(), onlineCapacity = source.onlineShelfCapacity) }
        viewModelScope.launch {
            shelfRepository.entries.collect { entries ->
                _state.update { current ->
                    val next = current.copy(allEntries = entries, loggedIn = source.isLoggedIn())
                    next.copy(entries = shelfRows(next.tab, entries, next.offline, next.downloads))
                }
            }
        }
        refreshLocalIndex()
        // Bookmarks are counted rather than listed here: this screen only needs to say how many a
        // deletion takes with it.
        viewModelScope.launch {
            bookmarkStore.bookmarks.collect { all ->
                _state.update { it.copy(bookmarkCounts = all.groupingBy { b -> b.bookId }.eachCount()) }
            }
        }
        // The shelf has nothing to show until the site's own list has been read, so the
        // first open pulls it instead of presenting an empty shelf and a refresh button.
        if (source.isLoggedIn()) syncOnlineShelfNow(quiet = true)
    }

    fun selectTab(tab: ShelfTab) {
        _state.update {
            val next = it.copy(
                tab = tab,
                // Multi-select belongs to the 书架 tab. Leaving it has to leave the mode as
                // well, because its toolbar is only drawn there — otherwise the next tab's
                // rows would carry tick boxes, and taps on them, with no way to dismiss it.
                selecting = false,
                selection = emptySet(),
                loggedIn = source.isLoggedIn(),
            )
            next.copy(entries = shelfRows(tab, next.allEntries, next.offline, next.downloads))
        }
    }

    /**
     * Re-reads everything this device holds: cached chapters and downloaded packs.
     *
     * Cheap enough to run on every return to the screen (one directory listing per book,
     * plus one small index per download) and it has to be: the detail screen caches chapters
     * and downloads whole books while this screen sits on the back stack, and only a re-read
     * notices. Doing it in the view model rather than watching the file system keeps the
     * reader's own writes — one file per chapter turned — off the main thread.
     */
    fun refreshLocalIndex() {
        viewModelScope.launch {
            publishLocalIndex(scanOffline(), scanDownloads())
        }
    }

    private suspend fun scanOffline(): Map<Int, OfflineBook> =
        runCatching { bookRepository.offlineBooks() }
            .getOrDefault(emptyList())
            .associateBy { it.bookId }

    /**
     * The packs on disk, re-read through the repository.
     *
     * [BookRepository.refreshPacks] is what makes this correct rather than merely fast: the
     * list also has to be re-read after the detail screen downloaded one, and the pack store
     * is the only thing that knows where those files are.
     */
    private suspend fun scanDownloads(): Map<Int, DownloadedBook> {
        runCatching { bookRepository.refreshPacks() }
        return bookRepository.downloadedBooks().associateBy { it.bookId }
    }

    private fun publishLocalIndex(
        offline: Map<Int, OfflineBook>,
        downloads: Map<Int, DownloadedBook>,
    ) = _state.update { current ->
        val next = current.copy(offline = offline, downloads = downloads)
        next.copy(entries = shelfRows(next.tab, next.allEntries, offline, downloads))
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
        // Unconditional, and deliberately before the session check below: chapters cached
        // and whole books downloaded on the detail screen are local changes, so a return to
        // the shelf has to notice them whether or not the account changed.
        refreshLocalIndex()
        if (syncInFlight) return
        val loggedIn = source.isLoggedIn()
        if (loggedIn == syncedWhileLoggedIn) return
        if (loggedIn) {
            syncOnlineShelfNow(quiet = true)
        } else {
            syncedWhileLoggedIn = false
            _state.update { it.copy(loggedIn = false, error = null, requiresLogin = false) }
        }
    }

    /**
     * The 刷新 button on the 书架 tab: the same sync as [onResumed], but paced.
     *
     * A tap inside the two-second window is swallowed silently — the answer to it is
     * the sync already running, and telling the user they tapped too fast is not worth
     * a message. The two automated callers ([onResumed], and the first open through
     * `init`) reach [syncOnlineShelfNow] instead: they are decided by the session
     * changing, not by a button, and being paced by a tap that happened to come first
     * would leave the screen showing the previous session's shelf.
     */
    fun syncOnlineShelf(quiet: Boolean = false) {
        if (!refreshThrottle.tryAcquire()) return
        syncOnlineShelfNow(quiet)
    }

    private fun syncOnlineShelfNow(quiet: Boolean = false) {
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
                    // The rows just written are as thin as the site's bookshelf page is; this
                    // is the only moment the app knows which books are missing a description.
                    backfillMissingMetadata(shelf.entries.map { it.book })
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

    // ------------------------------------------------------------------ metadata

    /**
     * Completes the rows the site's own bookshelf page left half-empty.
     *
     * `bookcase.php` is a table of 名称 / 作者 / 最新章节 and nothing else — no cover image and
     * no link to the book's own page — so a favourite that has never been opened in this app
     * arrives as a bare title and is drawn with the placeholder letter instead of artwork.
     * One read of the book's detail page fills in the cover, the 文库分类, the 状态 and the
     * 最后更新, and the result is persisted, so it costs the site one page per book ever.
     *
     * Deliberately bounded and unspectacular:
     *  * serial, through the source's own rate limiter — never a burst;
     *  * at most [MAX_METADATA_BACKFILL] books per pass, so a 300-book shelf is completed
     *    a few at a time across syncs rather than in one long crawl;
     *  * it stops at the first failure. A browser challenge or a lost session fails every
     *    remaining book identically, and asking the same question eight more times is exactly
     *    the behaviour the site's throttling is there to discourage.
     */
    private fun backfillMissingMetadata(books: List<Book>) {
        if (metadataBackfillJob?.isActive == true) return
        val pending = books
            .filter { it.needsFullerMetadata() }
            .map { it.bookId }
            .filterNot { it in metadataAttempted }
            .take(MAX_METADATA_BACKFILL)
        if (pending.isEmpty()) return

        metadataBackfillJob = viewModelScope.launch {
            var filled = 0
            for (bookId in pending) {
                // Recorded before the attempt, not after: a book the site has no cover for
                // would otherwise be asked about again on every pass, forever.
                metadataAttempted += bookId
                val summary = runCatching { bookRepository.bookSummary(bookId) }.getOrNull() ?: break
                shelfRepository.updateBook(summary)
                filled++
            }
            if (filled > 0) {
                _state.update { it.copy(message = "已补全 $filled 本书的封面与文库信息") }
            }
        }
    }

    /**
     * Whether a row is still only what the bookshelf page could say about it.
     *
     * The test is the *absence of everything the detail page adds*, not the absence of a
     * cover alone: some books genuinely have no cover on the site, and keying on the cover
     * would retry those forever. Once a book has a 文库分类 or an 更新日期 the app has read
     * its own page, and there is nothing more to learn.
     */
    private fun Book.needsFullerMetadata(): Boolean =
        coverUrl.isNullOrBlank() && category.isBlank() && updatedAt.isBlank()

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

            // The offline copies of the removed books are gone with them, so the 已缓存 tab
            // has to be rebuilt from a fresh listing rather than believe the record. A
            // downloaded pack is deliberately *not* deleted here: it is a copy of the text,
            // not a note about the account, and the user asked to leave the bookshelf — not
            // to lose the book. It stays visible under 已下载.
            val offline = scanOffline()
            _state.update {
                val next = it.copy(
                    offline = offline,
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
                next.copy(entries = shelfRows(next.tab, next.allEntries, offline, next.downloads))
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Deletes one book's chapters from this device and nothing else.
     *
     * The 已缓存 tab is about local space, so its delete is local: the site's bookshelf and
     * this app's reading progress are facts about the *book*, not about the copy, and
     * dropping them because someone wanted their storage back would lose data the user
     * never asked to lose. The shelf's own delete remains the one that reaches the account.
     */
    fun deleteOffline(entry: ShelfEntry) {
        val bookId = entry.book.bookId
        viewModelScope.launch {
            bookRepository.deleteOfflineCopy(bookId)
            // The record of what was cached lives on the shelf row; leaving it behind would
            // keep the "已缓存到本机" badge on the other tabs for chapters that are gone.
            shelfRepository.setCachedChapters(bookId, emptySet())
            publishLocalIndex(_state.value.offline - bookId, _state.value.downloads)
            _state.update { it.copy(message = "已删除《${entry.book.title}》的本地缓存") }
        }
    }

    /**
     * Deletes a downloaded book: the pack, its index, and the chapters imported from it.
     *
     * Everything local goes, because 已下载 is one row per downloaded book rather than per
     * file — deleting only the pack would leave the row gone while the text stayed on disk,
     * which is exactly the state this screen cannot show.
     */
    fun deleteDownloaded(entry: ShelfEntry) {
        val bookId = entry.book.bookId
        viewModelScope.launch {
            bookRepository.deleteLocalCopy(bookId)
            shelfRepository.setCachedChapters(bookId, emptySet())
            publishLocalIndex(scanOffline(), _state.value.downloads - bookId)
            _state.update { it.copy(message = "已删除《${entry.book.title}》的本机整本下载") }
        }
    }

    companion object {
        /**
         * How many books one metadata backfill pass may read.
         *
         * Every one of them is a page load against a site that throttles, so this is a
         * "few at a time" figure rather than a "finish the shelf" one.
         */
        private const val MAX_METADATA_BACKFILL = 8

        fun factory(container: AppContainer) = AppViewModelFactory<ShelfViewModel> {
            ShelfViewModel(
                it.shelfRepository,
                it.bookRepository,
                it.bookmarkStore,
                it.bookSource,
                it.refreshThrottle,
            )
        }
    }
}

/**
 * The shelf, as the app wires it up.
 *
 * This half owns the two things a preview cannot have — the view model and the lifecycle
 * hook that re-reads the shelf when the user comes back from logging in — plus the
 * snackbar, which reports what the view model did. Everything it *renders* lives in
 * [ShelfContent], which is stateless and therefore previewable (see
 * `ShelfScreenPreviews.kt`).
 */
@Composable
fun ShelfScreen(
    onOpenBook: (Book) -> Unit,
    onContinueReading: (Int, Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLogin: () -> Unit,
    viewModel: ShelfViewModel = viewModel(
        factory = ShelfViewModel.factory(com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

    // The body scrolls; the snackbar does not. Keeping the host out of the Column and
    // on the bottom edge is what makes 已同步 / 已补全 appear *below* the list, where a
    // shelf-level report belongs, instead of over the first rows the user is reading.
    Box(modifier = Modifier.fillMaxSize()) {
        ShelfContent(
            state = state,
            actions = ShelfActions(
                onSelectTab = viewModel::selectTab,
                onStartSelecting = viewModel::startSelecting,
                onStopSelecting = viewModel::stopSelecting,
                onToggleSelection = viewModel::toggleSelection,
                onToggleSelectAll = viewModel::toggleSelectAll,
                onRemoveSelected = viewModel::removeSelected,
                onSyncOnlineShelf = { viewModel.syncOnlineShelf() },
                onRemove = viewModel::remove,
                onDeleteOffline = viewModel::deleteOffline,
                onDeleteDownloaded = viewModel::deleteDownloaded,
                onOpenBook = onOpenBook,
                onContinueReading = onContinueReading,
                onOpenSearch = onOpenSearch,
                onOpenLogin = onOpenLogin,
            ),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}
