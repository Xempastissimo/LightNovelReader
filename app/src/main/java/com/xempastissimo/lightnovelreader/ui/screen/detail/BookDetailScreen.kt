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
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.domain.model.Volume
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.component.CoverImage
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.StateCrossfade
import com.xempastissimo.lightnovelreader.ui.component.pressHighlight
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val detail: BookDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val onShelf: Boolean = false,
    val progress: ReadingProgress? = null,
    val cachedChapterIds: Set<Int> = emptySet(),
    val downloading: Boolean = false,
    val downloadedChapters: Int = 0,
    val totalChapters: Int = 0,
    val message: String? = null,
) {
    val chapterCount: Int get() = detail?.chapters?.size ?: 0
}

/** Which of the detail screen's mutually exclusive bodies is on show. */
private enum class DetailPhase { LOADING, ERROR, CONTENT }

class BookDetailViewModel(
    private val bookId: Int,
    private val repository: BookRepository,
    private val shelfRepository: ShelfRepository,
    private val source: BookSource,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailUiState())
    val state: StateFlow<BookDetailUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        load()
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
        val detail = _state.value.detail ?: return
        if (!source.isLoggedIn()) {
            _state.update { it.copy(message = "请先登录，才能收藏到站点在线书架") }
            return
        }
        val wasOnShelf = _state.value.onShelf
        viewModelScope.launch {
            if (wasOnShelf) {
                shelfRepository.markOnline(bookId, online = false)
            } else {
                shelfRepository.add(detail.book)
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

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(container: AppContainer, bookId: Int) = AppViewModelFactory<BookDetailViewModel> {
            BookDetailViewModel(bookId, it.bookRepository, it.shelfRepository, it.bookSource)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Int,
    onBack: () -> Unit,
    onRead: (Int) -> Unit,
    viewModel: BookDetailViewModel = viewModel(
        factory = BookDetailViewModel.factory(
            com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current,
            bookId,
        ),
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
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = state.detail?.book?.title ?: "书籍详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load(forceRefresh = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = viewModel::toggleShelf) {
                        if (state.onShelf) {
                            Icon(Icons.Filled.Star, contentDescription = "已在书架", tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Icon(Icons.Outlined.Star, contentDescription = "加入书架")
                        }
                    }
                },
            )

            val phase = when {
                state.loading -> DetailPhase.LOADING
                state.error != null -> DetailPhase.ERROR
                else -> DetailPhase.CONTENT
            }

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
                        onAction = { viewModel.load(forceRefresh = true) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                    )

                    DetailPhase.CONTENT -> {
                        val detail = state.detail
                        if (detail != null) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp),
                            ) {
                                item { DetailHeader(detail = detail) }
                                item {
                                    DetailActions(
                                        state = state,
                                        chapterCount = detail.chapters.size,
                                        onRead = {
                                            val target = state.progress?.chapterId
                                                ?: detail.chapters.firstOrNull()?.chapterId
                                            if (target != null) onRead(target)
                                        },
                                        onDownload = viewModel::downloadAll,
                                        onCancelDownload = viewModel::cancelDownload,
                                    )
                                }
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
                                                onClick = { onRead(chapter.chapterId) },
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun DetailHeader(detail: BookDetail) {
    Row(modifier = Modifier.padding(16.dp)) {
        CoverImage(
            url = detail.book.coverUrl,
            title = detail.book.title,
            modifier = Modifier.size(width = 96.dp, height = 132.dp),
            cornerRadius = 8,
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = detail.book.title, style = MaterialTheme.typography.titleMedium)
            if (detail.book.author.isNotBlank()) {
                Text(
                    text = "作者：${detail.book.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.book.category.isNotBlank()) {
                Text(
                    text = "文库：${detail.book.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.book.status.isNotBlank()) {
                Text(
                    text = "状态：${detail.book.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.book.updatedAt.isNotBlank()) {
                Text(
                    text = "更新：${detail.book.updatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.book.latestChapter.isNotBlank()) {
                Text(
                    text = "最新：${detail.book.latestChapter}",
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
        DetailHeader(detail = sampleDetail)
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
        )
    }
}
