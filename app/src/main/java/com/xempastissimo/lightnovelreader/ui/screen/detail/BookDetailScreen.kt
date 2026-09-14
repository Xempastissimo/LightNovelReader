package com.xempastissimo.lightnovelreader.ui.screen.detail

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.network.RefreshThrottle
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.data.repo.DownloadedBook
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.data.repo.formatBytes
import com.xempastissimo.lightnovelreader.data.repo.formatDownloadDate
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.domain.model.Volume
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer
import com.xempastissimo.lightnovelreader.ui.component.CoverImage
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.StateCrossfade
import com.xempastissimo.lightnovelreader.ui.component.pressHighlight
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailUiState(
    /**
     * What the list this book was opened from already showed: title, cover, author, 文库.
     *
     * Drawn immediately so the screen is not a full-page spinner for as long as the source
     * takes to answer; [detail] supersedes it the moment it arrives. Null after a process
     * restart, where nothing was handed over and there is genuinely nothing to draw yet.
     */
    val summary: Book? = null,
    val detail: BookDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val onShelf: Boolean = false,
    val progress: ReadingProgress? = null,
    val cachedChapterIds: Set<Int> = emptySet(),
    val downloading: Boolean = false,
    val downloadedChapters: Int = 0,
    val totalChapters: Int = 0,
    /**
     * Whether the source publishes whole-book packs. The download is offered only when it
     * does: a source without packs must not grow a button that can only fail.
     */
    val packSupported: Boolean = false,
    /** The whole-book pack already on this device, if any. */
    val pack: DownloadedBook? = null,
    val packDownloading: Boolean = false,
    /** What the pack download is doing right now: 正在下载整本… / 正在导入 12/270 章… */
    val packPhase: String? = null,
    val message: String? = null,
) {
    /** The header the screen can draw right now: the source's own answer, or the list's copy. */
    val book: Book? get() = detail?.book ?: summary

    val chapterCount: Int get() = detail?.chapters?.size ?: 0

    /** True when the pack on this device leaves part of the catalogue to be read online. */
    val packIncomplete: Boolean get() = pack != null && pack.covered < pack.tocTotal
}

/** Which of the detail screen's mutually exclusive bodies is on show. */
private enum class DetailPhase { LOADING, ERROR, CONTENT }

class BookDetailViewModel(
    private val bookId: Int,
    private val repository: BookRepository,
    private val shelfRepository: ShelfRepository,
    private val source: BookSource,
    private val refreshThrottle: RefreshThrottle,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailUiState())
    val state: StateFlow<BookDetailUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var packJob: Job? = null

    init {
        // The list this book was just tapped in already showed its cover, title, author and
        // 文库, so the header is drawn from that copy while the source is read. 简介, the tags
        // and the chapter tree arrive later; none of them is needed to know what book this is.
        _state.update {
            it.copy(
                summary = repository.summary(bookId),
                packSupported = repository.supportsPackDownload,
                pack = repository.downloadedBook(bookId),
            )
        }
        load()
        // The pack directory is read from disk rather than trusted to be in memory: this
        // screen can be the first thing opened after a cold start, and another screen may
        // have downloaded (or deleted) a pack while this one sat on the back stack.
        viewModelScope.launch {
            runCatching { repository.refreshPacks() }
            val pack = repository.downloadedBook(bookId)
            _state.update {
                // A downloaded book carries the catalogue it was matched against, and that is
                // a real answer for it: opening one offline must not sit behind a
                // browser-engine timeout first. Only a stand-in — never a replacement for an
                // answer that has already arrived from the source.
                val offlineToc = pack?.tocDetail()?.takeIf { detail -> detail.chapters.isNotEmpty() }
                it.copy(pack = pack, detail = it.detail ?: offlineToc)
            }
        }
        viewModelScope.launch {
            shelfRepository.entries.collect { entries ->
                val entry = entries.firstOrNull { it.book.bookId == bookId }
                _state.update {
                    it.copy(
                        // "On the shelf" means on the *site's* shelf, because that is what
                        // the 书架 tab shows. Merely having read the book is not the same
                        // thing: the reader records progress by adding a local row too,
                        // and a filled star for a book the shelf does not list would be
                        // the app contradicting itself.
                        onShelf = entry?.online == true,
                        progress = entry?.progress,
                        cachedChapterIds = entry?.cachedChapterIds ?: repository.cachedChapterIds(bookId),
                    )
                }
            }
        }
    }

    fun load(forceRefresh: Boolean = false) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.detail(bookId, forceRefresh) }
                .onSuccess { detail ->
                    _state.update {
                        it.copy(
                            detail = detail,
                            loading = false,
                            error = null,
                            cachedChapterIds = repository.cachedChapterIds(bookId),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.toUserMessage()) }
                }
        }
    }

    /**
     * The 刷新 button: re-reads the detail page and its catalog, ignoring the cache.
     *
     * Paced because the button can be tapped repeatedly and every tap is a page load,
     * but a tap inside the two-second window is swallowed silently rather than
     * reported — the answer to it is already on its way. The screen's first read
     * ([load] from `init`) is not throttled: it is not a button press, and the user
     * opening a book should never wait out a window they never used.
     */
    fun refresh() {
        if (!refreshThrottle.tryAcquire()) return
        load(forceRefresh = true)
    }

    /**
     * The 重试 button on a failed body (full-screen or inline).
     *
     * Not paced, on purpose: the read that failed is not the read a previous tap started, and
     * a window opened by that tap swallowing this one would leave the failure on screen with
     * nothing to do about it (see AGENTS.md on `RefreshThrottle`).
     */
    fun retry() = load(forceRefresh = true)

    /**
     * Favourite toggle.
     *
     * Favouriting means "put this on the site's bookshelf", because that bookshelf is
     * what the 书架 tab shows. So the push to the site is the operation, and the local
     * row only ever mirrors its outcome — an add that the site never accepted is not a
     * favourite, it is just a book this app happens to have opened.
     *
     * Un-favouriting clears the mirror flag instead of deleting the local row: that row
     * also carries this app's reading progress and offline chapters, and the site's
     * shelf has nothing to do with either.
     */
    fun toggleShelf() {
        // The header may still be the list's copy: favouriting is about *which book* this is,
        // not about whether its catalogue has arrived, so the star works from the first frame.
        val book = _state.value.book ?: return
        if (!source.isLoggedIn()) {
            _state.update { it.copy(message = "请先登录，才能收藏到站点在线书架") }
            return
        }
        val wasOnShelf = _state.value.onShelf
        viewModelScope.launch {
            if (wasOnShelf) {
                shelfRepository.markOnline(bookId, online = false)
            } else {
                shelfRepository.add(book)
            }
            _state.update { it.copy(message = mirrorToOnlineShelf(remove = wasOnShelf)) }
        }
    }

    /** Pushes the change to the source's bookshelf and returns what to tell the user. */
    private suspend fun mirrorToOnlineShelf(remove: Boolean): String {
        val past = if (remove) "已从书架移除" else "已加入书架"
        return runCatching {
            if (remove) {
                source.removeFromOnlineShelf(bookId)
            } else {
                source.addToOnlineShelf(bookId)
            }
        }.fold(
            onSuccess = { accepted ->
                when {
                    !accepted -> "$past，但站点未确认这次改动"
                    remove -> "$past，站点在线书架已同步"
                    else -> {
                        shelfRepository.markOnline(bookId)
                        "$past，已同步到站点在线书架"
                    }
                }
            },
            onFailure = { error -> "$past；站点在线书架同步失败：${error.toUserMessage()}" },
        )
    }

    /** Downloads every chapter for offline reading, one at a time. */
    fun downloadAll() {
        val detail = _state.value.detail ?: return
        if (downloadJob?.isActive == true) return
        _state.update { it.copy(downloading = true, downloadedChapters = 0, totalChapters = detail.chapters.size) }
        downloadJob = viewModelScope.launch {
            runCatching {
                repository.downloadBook(
                    detail = detail,
                    onProgress = { done, total ->
                        _state.update { it.copy(downloadedChapters = done, totalChapters = total) }
                    },
                )
            }.onSuccess { done ->
                shelfRepository.add(detail.book)
                shelfRepository.setCachedChapters(bookId, repository.cachedChapterIds(bookId))
                _state.update {
                    it.copy(
                        downloading = false,
                        cachedChapterIds = repository.cachedChapterIds(bookId),
                        message = "已缓存 $done 章",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        downloading = false,
                        cachedChapterIds = repository.cachedChapterIds(bookId),
                        message = "缓存中断：${error.toUserMessage()}",
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(downloading = false, message = "已取消缓存") }
    }

    // ------------------------------------------------------------------ pack download

    /**
     * Downloads the source's own whole-book pack and imports it.
     *
     * One request for the entire book, which is why this exists next to 缓存全本 (a page
     * load per chapter). The catalogue is required and passed along: it is what the pack's
     * headings are matched against, and it is stored with the pack so the book can later
     * be opened with no network at all.
     */
    fun downloadPack() {
        val detail = _state.value.detail ?: return
        if (packJob?.isActive == true) return
        _state.update { it.copy(packDownloading = true, packPhase = "正在下载整本…") }
        packJob = viewModelScope.launch {
            runCatching {
                repository.downloadPack(bookId, detail) { phase ->
                    _state.update { it.copy(packPhase = phase) }
                }
            }.onSuccess { record ->
                shelfRepository.add(detail.book)
                shelfRepository.setCachedChapters(bookId, repository.cachedChapterIds(bookId))
                _state.update {
                    it.copy(
                        packDownloading = false,
                        packPhase = null,
                        pack = record,
                        cachedChapterIds = repository.cachedChapterIds(bookId),
                        message = "已下载整本 · 覆盖 ${record.covered}/${record.tocTotal} 章" +
                            " · ${formatBytes(record.bytes)}" +
                            if (record.covered < record.tocTotal) {
                                "（${record.tocTotal - record.covered} 章不在打包文件里，可在线阅读）"
                            } else {
                                ""
                            },
                    )
                }
            }.onFailure { error ->
                // A cancelled job is reported by [cancelPackDownload]; saying it failed
                // here as well would put a second, contradictory message on the screen.
                if (error is CancellationException) return@onFailure
                _state.update {
                    it.copy(
                        packDownloading = false,
                        packPhase = null,
                        message = "整本下载失败：${error.toUserMessage()}",
                    )
                }
            }
        }
    }

    fun cancelPackDownload() {
        packJob?.cancel()
        _state.update { it.copy(packDownloading = false, packPhase = null, message = "已取消整本下载") }
    }

    /**
     * Deletes everything this app holds for the book: the pack and the chapters.
     *
     * Deliberately the same reach as removing the row on the 已下载 tab — one action, one
     * meaning — while the site's bookshelf and the reading progress stay untouched, since
     * neither is a copy of the text.
     */
    fun deletePack() {
        viewModelScope.launch {
            repository.deleteLocalCopy(bookId)
            shelfRepository.setCachedChapters(bookId, emptySet())
            _state.update {
                it.copy(
                    pack = null,
                    cachedChapterIds = repository.cachedChapterIds(bookId),
                    message = "已删除本机下载的整本与章节",
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(container: AppContainer, bookId: Int) = AppViewModelFactory<BookDetailViewModel> {
            BookDetailViewModel(bookId, it.bookRepository, it.shelfRepository, it.bookSource, it.refreshThrottle)
        }
    }
}

/**
 * The book's page, as the app wires it up: the view model, and the snackbar that reports what
 * it did. Everything it *renders* lives in [BookDetailContent], which is stateless exactly so
 * that it can be previewed — including the states this screen exists to get right, a header
 * drawn from a list's copy while the catalogue is still being read, and a failure that keeps
 * what is known instead of replacing the page with an error.
 */
@Composable
fun BookDetailScreen(
    bookId: Int,
    onBack: () -> Unit,
    onRead: (Int) -> Unit,
    viewModel: BookDetailViewModel = viewModel(
        factory = BookDetailViewModel.factory(LocalAppContainer.current, bookId),
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

    Box(modifier = Modifier.fillMaxSize()) {
        BookDetailContent(
            state = state,
            actions = BookDetailActions(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                onToggleShelf = viewModel::toggleShelf,
                onRead = onRead,
                onDownload = viewModel::downloadAll,
                onCancelDownload = viewModel::cancelDownload,
                onDownloadPack = viewModel::downloadPack,
                onCancelPackDownload = viewModel::cancelPackDownload,
                onDeletePack = viewModel::deletePack,
            ),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

/**
 * Everything the detail screen can do, in one value.
 *
 * Bundled with a default for each entry so the body takes two parameters instead of ten, and
 * so a preview can render any state by supplying only the state: `@Preview` cannot reach a
 * view model, which is the whole reason this half exists.
 */
data class BookDetailActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onToggleShelf: () -> Unit = {},
    val onRead: (Int) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onDownloadPack: () -> Unit = {},
    val onCancelPackDownload: () -> Unit = {},
    val onDeletePack: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailContent(
    state: BookDetailUiState,
    actions: BookDetailActions = BookDetailActions(),
) {
    // Deleting a download is deleting text the user waited for, so it asks first — and
    // says what it does *not* touch, which is the part that is easy to fear.
    var confirmDeletePack by remember { mutableStateOf(false) }

    val phase = when {
        // Any header at all — the site's own answer or the list's copy — is enough to open
        // with: 简介 and the catalogue fill in below it rather than replacing it with a
        // spinner. Only a page with nothing to draw falls back to the full-screen states.
        state.book != null -> DetailPhase.CONTENT
        state.error != null -> DetailPhase.ERROR
        else -> DetailPhase.LOADING
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = state.book?.title ?: "书籍详情",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = actions.onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = actions.onToggleShelf) {
                    if (state.onShelf) {
                        Icon(Icons.Filled.Star, contentDescription = "已在书架", tint = MaterialTheme.colorScheme.tertiary)
                    } else {
                        Icon(Icons.Outlined.Star, contentDescription = "加入书架")
                    }
                }
            },
        )

        // The spinner hands over to the catalog with a fade, and the catalog
        // itself only re-arranges where a row actually moved.
        StateCrossfade(
            targetState = phase,
            label = "detail-body",
            modifier = Modifier.weight(1f),
        ) { body ->
            when (body) {
                DetailPhase.LOADING -> LoadingBox(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    label = "正在获取目录…",
                )

                DetailPhase.ERROR -> EmptyBox(
                    title = "加载失败",
                    hint = state.error,
                    actionLabel = "重试",
                    onAction = actions.onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                )

                DetailPhase.CONTENT -> {
                    val book = state.book
                    val detail = state.detail
                    if (book != null) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 32.dp),
                        ) {
                            item { DetailHeader(book = book) }
                            item {
                                DetailActions(
                                    state = state,
                                    chapterCount = state.chapterCount,
                                    onRead = {
                                        val target = state.progress?.chapterId
                                            ?: detail?.chapters?.firstOrNull()?.chapterId
                                        if (target != null) actions.onRead(target)
                                    },
                                    onDownload = actions.onDownload,
                                    onCancelDownload = actions.onCancelDownload,
                                    onDownloadPack = actions.onDownloadPack,
                                    onCancelPackDownload = actions.onCancelPackDownload,
                                    onDeletePack = { confirmDeletePack = true },
                                )
                            }
                            // What the screen is still waiting for, or what it could not get,
                            // said *under* the header — the cover, the title and whatever
                            // catalogue was already known stay where the user can read them.
                            if (state.loading) {
                                item {
                                    DetailStatusRow(
                                        text = if (detail == null) "正在获取目录…" else "正在重新获取…",
                                        busy = true,
                                    )
                                }
                            } else if (state.error != null) {
                                item {
                                    DetailStatusRow(
                                        text = "加载失败：${state.error}",
                                        onRetry = actions.onRetry,
                                    )
                                }
                            }
                            if (detail != null) {
                                if (detail.tags.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "标签：" + detail.tags.joinToString(" "),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                                if (detail.intro.isNotBlank()) {
                                    item { IntroBlock(intro = detail.intro) }
                                }
                                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

                                if (detail.chapters.isEmpty()) {
                                    item {
                                        EmptyBox(
                                            title = "没有可用目录",
                                            hint = "该书可能已下架或站点未提供在线阅读",
                                        )
                                    }
                                } else {
                                    detail.volumes.forEach { volume ->
                                        item(key = "volume-${volume.volumeId}-${volume.title}") {
                                            VolumeHeader(
                                                title = volume.title,
                                                count = volume.chapters.size,
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                        items(volume.chapters, key = { it.chapterId }) { chapter ->
                                            ChapterRow(
                                                chapter = chapter,
                                                current = chapter.chapterId == state.progress?.chapterId,
                                                cached = state.cachedChapterIds.contains(chapter.chapterId),
                                                onClick = { actions.onRead(chapter.chapterId) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDeletePack) {
        AlertDialog(
            onDismissRequest = { confirmDeletePack = false },
            title = { Text("删除本机下载？") },
            text = {
                Text("会删除打包下载的 txt 与导入的章节，站点在线书架与阅读进度都不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeletePack = false
                        actions.onDeletePack()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePack = false }) { Text("取消") }
            },
        )
    }
}

/**
 * One line under 开始阅读/缓存全本: what the screen is waiting for, or why it could not finish.
 *
 * Inline rather than a whole-screen state because the header — and a catalogue that came from
 * a downloaded pack — are already readable, and swapping them for an error would throw away
 * the only thing the user can still use.
 */
@Composable
private fun DetailStatusRow(
    text: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun DetailHeader(book: Book) {
    Row(modifier = Modifier.padding(16.dp)) {
        CoverImage(
            url = book.coverUrl,
            title = book.title,
            modifier = Modifier.size(width = 96.dp, height = 132.dp),
            cornerRadius = 8,
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = book.title, style = MaterialTheme.typography.titleMedium)
            if (book.author.isNotBlank()) {
                Text(
                    text = "作者：${book.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.category.isNotBlank()) {
                Text(
                    text = "文库：${book.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.status.isNotBlank()) {
                Text(
                    text = "状态：${book.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.updatedAt.isNotBlank()) {
                Text(
                    text = "更新：${book.updatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.latestChapter.isNotBlank()) {
                Text(
                    text = "最新：${book.latestChapter}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailActions(
    state: BookDetailUiState,
    chapterCount: Int,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDownloadPack: () -> Unit,
    onCancelPackDownload: () -> Unit,
    onDeletePack: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRead,
                enabled = chapterCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.progress != null) "继续阅读" else "开始阅读")
            }
            if (state.downloading) {
                OutlinedButton(onClick = onCancelDownload, modifier = Modifier.weight(1f)) {
                    Text("取消缓存")
                }
            } else {
                FilledTonalButton(onClick = onDownload, enabled = chapterCount > 0, modifier = Modifier.weight(1f)) {
                    Text(text = "缓存全本", maxLines = 1)
                }
            }
        }
        if (state.downloading) {
            val fraction = if (state.totalChapters > 0) {
                state.downloadedChapters.toFloat() / state.totalChapters
            } else {
                0f
            }
            Text(
                text = "正在缓存 ${state.downloadedChapters}/${state.totalChapters} 章（串行请求，请勿频繁操作）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(top = 4.dp),
            )
        } else if (state.cachedChapterIds.isNotEmpty()) {
            Text(
                text = "已缓存 ${state.cachedChapterIds.size} 章到本机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.packSupported) {
            PackDownloadAction(
                state = state,
                chapterCount = chapterCount,
                onDownload = onDownloadPack,
                onCancel = onCancelPackDownload,
                onDelete = onDeletePack,
            )
        }
    }
}

/**
 * The whole-book (站点打包) download, and what this device already holds.
 *
 * Kept separate from the 缓存全本 pair above because the two are genuinely different
 * offers: 缓存全本 fetches a page per chapter, while the pack is one request for the entire
 * book — and the pack comes with no illustrations, which the hint says out loud so it is
 * not read as a defect of this app.
 */
@Composable
private fun PackDownloadAction(
    state: BookDetailUiState,
    chapterCount: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        val pack = state.pack
        when {
            state.packDownloading -> {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消下载")
                }
                Text(
                    text = state.packPhase ?: "正在下载整本…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // Indeterminate on purpose: the download host answers with a chunked
                // response and no Content-Length, so any percentage would be invented.
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(top = 4.dp),
                )
            }

            pack != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                        Text("重新下载", maxLines = 1)
                    }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text("删除本地下载", maxLines = 1)
                    }
                }
                Text(
                    text = buildString {
                        append("已下载整本 · 覆盖 ${pack.covered}/${pack.tocTotal} 章")
                        append(" · ${formatBytes(pack.bytes)}")
                        val date = formatDownloadDate(pack.downloadedAt)
                        if (date.isNotEmpty()) append(" · $date")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.packIncomplete) {
                    Text(
                        text = "${pack.tocTotal - pack.covered} 章不在打包文件里（站点快照较旧），这些章节可在线阅读",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = chapterCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下载全本（站点打包）", maxLines = 1)
                }
                Text(
                    text = "站点把整本打包成一个 txt，一次请求就能下完，比逐章缓存快得多；打包文件不含插图。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun IntroBlock(intro: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text = "内容简介", style = MaterialTheme.typography.titleSmall)
        Text(
            text = intro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun VolumeHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = "$count 章",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    current: Boolean,
    cached: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            // Opening a chapter replaces the whole screen; the wash under the finger
            // is what tells the user the tap landed before the reader appears.
            .pressHighlight(interactionSource)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (cached) {
            Text(
                text = "离线",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (current) {
            Text(
                text = "  上次读到",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val sampleDetail = BookDetail(
    book = Book(
        bookId = 12345,
        title = "刀剑神域",
        author = "川原砾",
        category = "轻小说",
        status = "连载中",
        latestChapter = "第 28 卷",
        updatedAt = "2026-09-01",
    ),
    intro = "二〇二二年，人类成功制作出完全潜行型虚拟实境大型多人在线角色扮演游戏「Sword Art Online」，" +
        "但玩家们很快发现一旦登入就无法登出。Creator「茅场晶彦」宣布：只有通关全部一百层，" +
        "全体玩家才能离开这个世界——而死亡在游戏中的角色，在现实中也会死去。",
    tags = listOf("冒险", "科幻", "恋爱"),
    volumes = listOf(
        Volume(volumeId = 1, title = "第一卷 艾恩葛朗特", chapters = listOf(
            Chapter(chapterId = 1, title = "第 1 章 欢迎来到 MMO RPG", volumeId = 1, volumeTitle = "第一卷", index = 0),
            Chapter(chapterId = 2, title = "第 2 章 封测者", volumeId = 1, volumeTitle = "第一卷", index = 1),
            Chapter(chapterId = 3, title = "第 3 章 奇怪的少女", volumeId = 1, volumeTitle = "第一卷", index = 2),
        )),
        Volume(volumeId = 2, title = "第二卷 艾恩葛朗特", chapters = listOf(
            Chapter(chapterId = 4, title = "第 4 章 红色獠牙", volumeId = 2, volumeTitle = "第二卷", index = 3),
            Chapter(chapterId = 5, title = "第 5 章 铁匠", volumeId = 2, volumeTitle = "第二卷", index = 4),
        )),
    ),
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DetailHeaderPreview() {
    LightNovelReaderTheme {
        DetailHeader(book = sampleDetail.book)
    }
}

@Preview(name = "状态行 · 正在获取目录", showBackground = true, widthDp = 360)
@Composable
private fun DetailStatusBusyPreview() {
    LightNovelReaderTheme {
        DetailStatusRow(text = "正在获取目录…", busy = true)
    }
}

@Preview(name = "状态行 · 加载失败", showBackground = true, widthDp = 360)
@Composable
private fun DetailStatusFailedPreview() {
    LightNovelReaderTheme {
        DetailStatusRow(text = "加载失败：网络请求失败", onRetry = {})
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun IntroBlockPreview() {
    LightNovelReaderTheme {
        IntroBlock(intro = sampleDetail.intro)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun VolumeHeaderPreview() {
    LightNovelReaderTheme {
        VolumeHeader(title = "第一卷 艾恩葛朗特", count = 3)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ChapterRowPreview() {
    LightNovelReaderTheme {
        ChapterRow(
            chapter = sampleDetail.chapters.first(),
            current = false,
            cached = false,
            onClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ChapterRowCurrentPreview() {
    LightNovelReaderTheme {
        ChapterRow(
            chapter = sampleDetail.chapters.first(),
            current = true,
            cached = true,
            onClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DetailActionsPreview() {
    LightNovelReaderTheme {
        DetailActions(
            state = BookDetailUiState(onShelf = true, progress = ReadingProgress(bookId = 12345, chapterId = 2, chapterIndex = 1)),
            chapterCount = 5,
            onRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDownloadPack = {},
            onCancelPackDownload = {},
            onDeletePack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DetailActionsDownloadingPreview() {
    LightNovelReaderTheme {
        DetailActions(
            state = BookDetailUiState(downloading = true, downloadedChapters = 2, totalChapters = 5),
            chapterCount = 5,
            onRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDownloadPack = {},
            onCancelPackDownload = {},
            onDeletePack = {},
        )
    }
}

@Preview(name = "整本下载 · 可下载", showBackground = true, widthDp = 360)
@Composable
private fun DetailActionsPackAvailablePreview() {
    LightNovelReaderTheme {
        DetailActions(
            state = BookDetailUiState(packSupported = true),
            chapterCount = 270,
            onRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDownloadPack = {},
            onCancelPackDownload = {},
            onDeletePack = {},
        )
    }
}

@Preview(name = "整本下载 · 下载中", showBackground = true, widthDp = 360)
@Composable
private fun DetailActionsPackDownloadingPreview() {
    LightNovelReaderTheme {
        DetailActions(
            state = BookDetailUiState(
                packSupported = true,
                packDownloading = true,
                packPhase = "正在导入 120/270 章…",
            ),
            chapterCount = 270,
            onRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDownloadPack = {},
            onCancelPackDownload = {},
            onDeletePack = {},
        )
    }
}

@Preview(name = "整本下载 · 已完成", showBackground = true, widthDp = 360)
@Composable
private fun DetailActionsPackDonePreview() {
    LightNovelReaderTheme {
        DetailActions(
            state = BookDetailUiState(packSupported = true, pack = samplePack(covered = 270, tocTotal = 270)),
            chapterCount = 270,
            onRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDownloadPack = {},
            onCancelPackDownload = {},
            onDeletePack = {},
        )
    }
}

@Preview(name = "整本下载 · 覆盖不全", showBackground = true, widthDp = 360)
@Composable
private fun DetailActionsPackPartialPreview() {
    LightNovelReaderTheme {
        DetailActions(
            state = BookDetailUiState(packSupported = true, pack = samplePack(covered = 262, tocTotal = 270)),
            chapterCount = 270,
            onRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDownloadPack = {},
            onCancelPackDownload = {},
            onDeletePack = {},
        )
    }
}

/** A pack record for previews: one book, two volumes, no real download behind it. */
private fun samplePack(covered: Int, tocTotal: Int): DownloadedBook = DownloadedBook(
    bookId = 12345,
    book = sampleDetail.book,
    intro = sampleDetail.intro,
    charsetName = "UTF-8",
    sourceUrl = "https://dl.wenku8.com/down.php?type=utf8&node=1&id=12345",
    bytes = 6_778_311,
    downloadedAt = 1_789_225_000_000,
    tocTotal = tocTotal,
    covered = covered,
    slices = emptyMap(),
    volumes = sampleDetail.volumes,
)
