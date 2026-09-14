package com.xempastissimo.lightnovelreader.ui.screen.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.data.repo.BookmarkStore
import com.xempastissimo.lightnovelreader.data.repo.ImageLoader
import com.xempastissimo.lightnovelreader.data.repo.PageTurnMode
import com.xempastissimo.lightnovelreader.data.repo.ReaderSettings
import com.xempastissimo.lightnovelreader.data.repo.ReaderTheme
import com.xempastissimo.lightnovelreader.data.repo.SettingsRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Bookmark
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.Motion
import com.xempastissimo.lightnovelreader.ui.component.pagerPageDepth
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme
import com.xempastissimo.lightnovelreader.ui.theme.ReaderPalette
import com.xempastissimo.lightnovelreader.ui.theme.prefersDarkInk
import com.xempastissimo.lightnovelreader.ui.theme.readerBarPalette
import com.xempastissimo.lightnovelreader.ui.theme.readerPalette
import com.xempastissimo.lightnovelreader.ui.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReaderUiState(
    val detail: BookDetail? = null,
    val content: ChapterContent? = null,
    val chapterIndex: Int = 0,
    /**
     * Where in the freshly loaded chapter the reader should land.
     *
     * A paragraph plus a token rather than a plain index: the reader owns its pagination, so it
     * resolves the paragraph against pages only it can compute — and asking to land on the
     * paragraph it is already on has to look different from having already honoured that request.
     */
    val anchor: ReaderAnchor = ReaderAnchor(),
    val loading: Boolean = true,
    val error: String? = null,
    val requiresLogin: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
    /**
     * Whether the night page should be `#000000` rather than the warm near-black.
     *
     * An app-level setting, not a reader one: it says something about the screen, and turning it on
     * from the reader's own 夜间 chip has to change the app behind the reader too.
     */
    val oledBlack: Boolean = false,
    /**
     * Whether [settings] are the user's stored ones rather than the stock defaults.
     *
     * DataStore answers asynchronously, so for the first frame or two the state still holds
     * `ReaderSettings()` — whose 米黄 theme is *not* what most readers of a night theme see.
     * The screen needs to know the difference to avoid painting that stand-in, and to avoid
     * paginating for a font size the user never chose.
     */
    val settingsLoaded: Boolean = false,
    val showMenu: Boolean = true,
    /** True when this book is downloaded whole, which is what local bookmarks require. */
    val canBookmark: Boolean = false,
    /** This book's bookmarks, newest first. */
    val bookmarks: List<Bookmark> = emptyList(),
    /** The paragraph the reader is showing right now, as the reader itself counts. */
    val currentParagraph: Int = 0,
)

/** A one-shot request to land on paragraph [paragraph]; [token] makes a repeat a new request. */
data class ReaderAnchor(val paragraph: Int = 0, val token: Long = 0L)

class ReaderViewModel(
    private val bookId: Int,
    private val startChapterId: Int,
    private val repository: BookRepository,
    private val shelfRepository: ShelfRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkStore: BookmarkStore,
    private val imageLoader: ImageLoader,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Distinguishes one "land here" request from the next, including a repeat of the same one. */
    private var anchorToken = 0L

    /** The chapter-by-chapter prefetch, while one is running. */
    private var prefetchJob: Job? = null

    /** Hides the overlay [CONTROLS_AUTO_HIDE_MILLIS] after the last interaction. */
    private val overlayTimer = OverlayAutoHideTimer(
        scope = viewModelScope,
        timeoutMillis = CONTROLS_AUTO_HIDE_MILLIS,
    ) {
        _state.update { it.copy(showMenu = false) }
    }

    init {
        // Deliberately does *not* re-paginate. Pages are measured against the real screen in the
        // Compose layer, so there is nothing here for a font size to invalidate: the page's own
        // layout picks the new settings up on the next frame.
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { settings ->
                _state.update { it.copy(settings = settings, settingsLoaded = true) }
            }
        }
        viewModelScope.launch {
            settingsRepository.appSettings.collect { app ->
                _state.update { it.copy(oledBlack = app.oledBlack) }
            }
        }
        viewModelScope.launch {
            bookmarkStore.bookmarks.collect { all ->
                _state.update { it.copy(bookmarks = all.filter { bookmark -> bookmark.bookId == bookId }) }
            }
        }
        // Bookmarks are for downloaded books only, so the reader has to know whether this one is.
        // Read from disk rather than trusted to memory: this screen can be the first thing opened
        // after a cold start.
        viewModelScope.launch {
            runCatching { repository.refreshPacks() }
            _state.update { it.copy(canBookmark = repository.downloadedBook(bookId) != null) }
        }
        showControls()
        loadBook()
    }

    private fun loadBook() {
        _state.update { it.copy(loading = true, error = null, requiresLogin = false) }
        viewModelScope.launch {
            runCatching { repository.detail(bookId) }
                .onSuccess { detail ->
                    val chapters = detail.chapters
                    val target = chapters.firstOrNull { it.chapterId == startChapterId }
                        ?: shelfRepository.progress(bookId)?.let { progress ->
                            chapters.firstOrNull { it.chapterId == progress.chapterId }
                        }
                        ?: chapters.firstOrNull()
                    if (target == null) {
                        _state.update { it.copy(detail = detail, loading = false, error = "该书没有可用章节") }
                        return@onSuccess
                    }
                    _state.update {
                        it.copy(
                            detail = detail,
                            chapterIndex = target.index,
                            loading = true,
                        )
                    }
                    loadChapter(target.chapterId, resumeParagraph = startChapterId <= 0)
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.toUserMessage()) }
                }
        }
    }

    /** Opens a chapter from the list, landing on its beginning. */
    fun openChapter(chapterId: Int) {
        val detail = _state.value.detail ?: return
        val index = detail.chapters.indexOfFirst { it.chapterId == chapterId }
        if (index < 0) return
        _state.update { it.copy(chapterIndex = index) }
        viewModelScope.launch { loadChapter(chapterId, resumeParagraph = false) }
    }

    /**
     * Opens whichever chapter a bookmark names, landing on the paragraph it marked.
     *
     * A bookmark in the chapter already on screen is a jump rather than a load: re-reading the
     * chapter would cost a request to end up with the same text.
     */
    fun openBookmark(bookmark: Bookmark) {
        if (_state.value.content?.chapterId == bookmark.chapterId) {
            jumpToParagraph(bookmark.paragraphIndex)
            return
        }
        val detail = _state.value.detail ?: return
        val index = detail.chapters.indexOfFirst { it.chapterId == bookmark.chapterId }
        if (index < 0) return
        _state.update { it.copy(chapterIndex = index) }
        viewModelScope.launch {
            loadChapter(
                chapterId = bookmark.chapterId,
                resumeParagraph = false,
                landOnParagraph = bookmark.paragraphIndex,
            )
        }
    }

    fun nextChapter() {
        val detail = _state.value.detail ?: return
        val next = detail.chapters.getOrNull(_state.value.chapterIndex + 1) ?: return
        openChapter(next.chapterId)
    }

    fun previousChapter() {
        val detail = _state.value.detail ?: return
        val previous = detail.chapters.getOrNull(_state.value.chapterIndex - 1) ?: return
        openChapter(previous.chapterId)
    }

    /**
     * Turns to the neighbouring chapter because a swipe ran off the end of this one.
     *
     * Unlike the 上一章 / 下一章 buttons, which land on a chapter's first page so it can
     * be read forward, this lands wherever the reader was heading: the start of the next
     * chapter going forward, the *end* of the previous one going back. Otherwise swiping
     * back would drop the reader at the beginning of a chapter they had already finished.
     *
     * Returns `false` when there is no such chapter, so the caller can leave the gesture
     * alone.
     */
    fun turnChapter(forward: Boolean): Boolean {
        // A chapter already on its way in must not be overtaken by another swipe: the page
        // count on screen is still the previous chapter's until it lands, so a second turn
        // would be measured against pages that no longer describe anything.
        if (_state.value.loading) return false
        val detail = _state.value.detail ?: return false
        val target = _state.value.chapterIndex + if (forward) 1 else -1
        val chapter = detail.chapters.getOrNull(target) ?: return false
        _state.update { it.copy(chapterIndex = target) }
        viewModelScope.launch {
            loadChapter(chapter.chapterId, resumeParagraph = false, landOnLastPage = !forward)
        }
        return true
    }

    private suspend fun loadChapter(
        chapterId: Int,
        resumeParagraph: Boolean,
        landOnLastPage: Boolean = false,
        landOnParagraph: Int? = null,
    ) {
        val chapter = _state.value.detail?.chapters?.firstOrNull { it.chapterId == chapterId }
        _state.update { it.copy(loading = true, error = null, requiresLogin = false) }
        runCatching { repository.content(bookId, chapterId, chapter?.title.orEmpty()) }
            .onSuccess { content ->
                val paragraphs = content.paragraphs
                val lastParagraph = (paragraphs.size - 1).coerceAtLeast(0)
                val resume = when {
                    landOnParagraph != null -> landOnParagraph.coerceIn(0, lastParagraph)

                    resumeParagraph -> shelfRepository.progress(bookId)
                        ?.takeIf { it.chapterId == chapterId }
                        ?.paragraphIndex
                        ?.coerceIn(0, lastParagraph)
                        ?: 0

                    landOnLastPage -> lastParagraph
                    else -> 0
                }
                anchorToken++
                _state.update {
                    it.copy(
                        content = content,
                        anchor = ReaderAnchor(resume, anchorToken),
                        currentParagraph = resume,
                        loading = false,
                        error = null,
                    )
                }
                // Recorded here rather than left to the reader's own position listener. Opening a
                // chapter lands on a paragraph the reader may already be showing — the start of a
                // chapter read after one that also ended at its start — and a position that does
                // not change produces nothing for that listener to observe, which would leave the
                // stored position pointing at the chapter just left.
                saveProgress(resume)
                prefetchNeighbours(atChapterStart = resume == 0)
            }
            .onFailure { error ->
                val requiresLogin =
                    error is com.xempastissimo.lightnovelreader.data.network.HttpFailure.AuthRequired
                _state.update {
                    it.copy(loading = false, error = error.toUserMessage(), requiresLogin = requiresLogin)
                }
            }
    }

    // ------------------------------------------------------------------ prefetch

    /**
     * Fetches the neighbouring chapters before the reader asks for them.
     *
     * Reads through the repository, so a prefetched chapter is written to the offline cache on
     * the way — which is what makes the turn instant rather than merely sooner, and what keeps the
     * number of requests for a book read start to finish exactly what it was: every chapter is
     * still fetched once, just earlier.
     *
     * A prefetch that fails is silent. The chapter being read is fine, and an error about a
     * chapter nobody has asked for yet would be noise over a working page.
     */
    private fun prefetchNeighbours(atChapterStart: Boolean) {
        if (_state.value.loading || _state.value.error != null) return
        val chapters = _state.value.detail?.chapters ?: return
        val targets = prefetchTargets(
            chapterIndex = _state.value.chapterIndex,
            chapterCount = chapters.size,
            atChapterStart = atChapterStart,
            isCached = { index -> repository.isCached(bookId, chapters[index].chapterId) },
        )
        if (targets.isEmpty()) return
        val plan = targets.map { index -> chapters[index].chapterId to chapters[index].title }

        // Replaced rather than queued: only a chapter change reaches here, and the new chapter's
        // neighbour is what is worth having. A cancelled fetch writes nothing — the chapter cache
        // renames its file into place — and the site only ever sees the one request.
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            for ((chapterId, title) in plan) {
                val prefetched = runCatching { repository.content(bookId, chapterId, title) }
                    .getOrNull() ?: continue
                // Warm the plates as well as the text: an 插图 chapter is one image per page, and
                // `ImageLoader` caches by URL and width, which is exactly the pair the page asks
                // with — so the decoded plate is there when the page is drawn.
                for (url in prefetched.illustrations.take(MAX_PREFETCHED_ILLUSTRATIONS)) {
                    runCatching { imageLoader.load(url, maxWidth = ILLUSTRATION_MAX_WIDTH) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ position

    /**
     * Publishes a request to land on [paragraph] in the chapter already on screen.
     *
     * The reader owns its pagination, so "which page is this paragraph on" is a question only it
     * can answer; this hands it the paragraph and a fresh token to answer against.
     */
    fun jumpToParagraph(paragraph: Int) {
        val target = paragraph.coerceAtLeast(0)
        anchorToken++
        _state.update { it.copy(anchor = ReaderAnchor(target, anchorToken), currentParagraph = target) }
    }

    /** Tap on the page: hide the overlay when it is up, reveal it when it is not. */
    fun toggleMenu() {
        if (_state.value.showMenu) hideControls() else showControls()
    }

    /** Reveals the overlay and (re)starts the auto-hide countdown. */
    fun showControls() {
        _state.update { it.copy(showMenu = true) }
        overlayTimer.arm()
    }

    /** Hides the overlay immediately, e.g. when the user taps the page again. */
    fun hideControls() {
        overlayTimer.cancel()
        _state.update { it.copy(showMenu = false) }
    }

    /**
     * Keeps the overlay on screen while a panel (settings sheet, chapter list, bookmarks) is
     * open, and restarts the countdown once it closes: hiding the controls under
     * an open panel would look like a glitch.
     */
    fun setControlsPinned(pinned: Boolean) {
        if (overlayTimer.isPinned == pinned) return
        if (pinned) {
            overlayTimer.pin()
            _state.update { it.copy(showMenu = true) }
        } else {
            overlayTimer.unpin()
            if (_state.value.showMenu) overlayTimer.arm()
        }
    }

    fun setFontSize(value: Float) = viewModelScope.launch { settingsRepository.setFontSize(value) }

    fun setLineHeight(value: Float) = viewModelScope.launch { settingsRepository.setLineHeight(value) }

    fun setParagraphSpacing(value: Int) = viewModelScope.launch { settingsRepository.setParagraphSpacing(value) }

    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { settingsRepository.setReaderTheme(theme) }

    fun setPageTurnMode(mode: PageTurnMode) = viewModelScope.launch { settingsRepository.setPageTurnMode(mode) }

    /**
     * OLED pure black, from the long press on the 夜间 chip of the reading sheet.
     *
     * Turning it on also selects 夜间, for the same reason the settings page's long press also
     * selects 深色: pure black belongs to the dark page, and switching it on while reading on 米黄
     * would be a gesture with nothing to see. Off leaves the reading theme alone.
     */
    fun toggleOledBlack() {
        val enabled = !_state.value.oledBlack
        viewModelScope.launch {
            if (enabled) settingsRepository.setReaderTheme(ReaderTheme.DARK)
            settingsRepository.setOledBlack(enabled)
        }
    }

    // ------------------------------------------------------------------ bookmarks

    /** Adds a bookmark at the paragraph on screen, or removes the one already there. */
    fun toggleBookmarkAtCurrentParagraph() {
        val content = _state.value.content ?: return
        if (!_state.value.canBookmark) return
        val paragraph = _state.value.currentParagraph
        val existing = _state.value.bookmarks.any {
            it.chapterId == content.chapterId && it.paragraphIndex == paragraph
        }
        viewModelScope.launch {
            if (existing) {
                bookmarkStore.remove(bookId, content.chapterId, paragraph)
            } else {
                bookmarkStore.add(
                    Bookmark(
                        bookId = bookId,
                        chapterId = content.chapterId,
                        chapterIndex = _state.value.chapterIndex,
                        chapterTitle = content.title,
                        paragraphIndex = paragraph,
                        excerpt = content.paragraphs.getOrNull(paragraph).orEmpty().take(EXCERPT_LENGTH),
                    ),
                )
            }
        }
    }

    fun removeBookmark(bookmark: Bookmark) = viewModelScope.launch {
        bookmarkStore.remove(bookmark.bookId, bookmark.chapterId, bookmark.paragraphIndex)
    }

    // ------------------------------------------------------------------ progress

    /**
     * Stores the reading position; called as the reader's paragraph changes.
     *
     * [paragraph] indexes `ChapterContent.paragraphs` rather than counting pages: pages depend on
     * the font size and the window, so a stored page number is wrong the moment either changes,
     * while the paragraph the user stopped at does not move.
     */
    fun saveProgress(paragraph: Int) {
        if (paragraph < 0) return
        val detail = _state.value.detail ?: return
        val content = _state.value.content ?: return
        val chapter = detail.chapters.getOrNull(_state.value.chapterIndex) ?: return
        if (_state.value.currentParagraph != paragraph) {
            _state.update { it.copy(currentParagraph = paragraph) }
        }
        val total = content.paragraphs.size
        val percent = if (total <= 0) 0f else ((paragraph + 1).toFloat() / total).coerceIn(0f, 1f)
        viewModelScope.launch {
            shelfRepository.add(detail.book)
            shelfRepository.saveProgress(
                ReadingProgress(
                    bookId = bookId,
                    chapterId = chapter.chapterId,
                    chapterIndex = chapter.index,
                    paragraphIndex = paragraph,
                    percentInChapter = percent,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    companion object {
        /** How long the overlay (title bar + chapter bar) stays up after a tap. */
        const val CONTROLS_AUTO_HIDE_MILLIS = 5_000L

        /**
         * How much of a bookmarked paragraph is kept for the bookmark list.
         *
         * The paragraph itself is not stored — the chapter it lives in already is, on disk — so
         * this is only enough to recognise the place by.
         */
        const val EXCERPT_LENGTH = 40

        /** The width illustrations are decoded at, shared with `IllustrationPage`. */
        const val ILLUSTRATION_MAX_WIDTH = 1080

        fun factory(container: AppContainer, bookId: Int, chapterId: Int) =
            AppViewModelFactory<ReaderViewModel> {
                ReaderViewModel(
                    bookId = bookId,
                    startChapterId = chapterId,
                    repository = it.bookRepository,
                    shelfRepository = it.shelfRepository,
                    settingsRepository = it.settingsRepository,
                    bookmarkStore = it.bookmarkStore,
                    imageLoader = it.imageLoader,
                )
            }
    }
}

/** The pages measured for one chapter, tagged so they can never stand in for another chapter's. */
private data class MeasuredPages(
    val chapterId: Int = -1,
    val pages: List<ReaderPageContent> = emptyList(),
    val ready: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    bookId: Int,
    startChapterId: Int,
    onBack: () -> Unit,
    onOpenChapters: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.factory(
            com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current,
            bookId,
            startChapterId,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showChapterMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    val settings = state.settings
    val content = state.content
    val horizontal = settings.pageTurnMode == PageTurnMode.HORIZONTAL

    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { settings.horizontalPaddingDp.dp.roundToPx() }
    val verticalPaddingPx = with(density) { READER_VERTICAL_PADDING.roundToPx() }
    val lineHeightPx = with(density) { readerLineHeight(settings).roundToPx() }
    val paragraphSpacingPx = with(density) { settings.paragraphSpacingDp.dp.roundToPx() }
    val measurer = rememberTextMeasurer()

    // One style object, used both to measure the text and to draw it.
    //
    // Not tidiness: `Text` merges the explicit `fontSize`/`lineHeight`/`textAlign` parameters into
    // whatever `style` it is given, and anything that parameter does not name comes from that
    // style. Measuring with a separately built one is how the two quietly disagree — a
    // `letterSpacing`, a font feature, a line-height style — and a disagreement about width is a
    // page that measures as fitting and draws one character too wide. The theme in this app
    // happens to leave `LocalTextStyle` at `TextStyle.Default`, so they agree today; sharing the
    // object is what keeps that from being a coincidence.
    val pageTextStyle = TextStyle(
        fontSize = settings.fontSizeSp.sp,
        lineHeight = readerLineHeight(settings),
        textAlign = TextAlign.Justify,
    )

    var measured by remember { mutableStateOf(MeasuredPages()) }
    // Pages belong to the chapter they were measured for, so a chapter that has just changed can
    // never be drawn with the previous chapter's pages.
    val activePages = if (horizontal && measured.chapterId == content?.chapterId) measured.pages else emptyList()
    val pagesReady = horizontal && measured.chapterId == content?.chapterId && measured.ready
    val pageCount = activePages.size

    val blockItems = remember(content) { content?.blocks?.let { verticalItems(it) } ?: emptyList() }
    val chapters = state.detail?.chapters.orEmpty()
    val hasPreviousChapter = state.chapterIndex > 0
    val hasNextChapter = state.chapterIndex < chapters.size - 1

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { activePages.size })
    val listState = rememberLazyListState()

    // Where the reader is, in paragraphs. Owned here because only the reader knows its pagination;
    // the view model is told, and stores it.
    var lastParagraph by remember { mutableStateOf(0) }
    var consumedAnchorToken by remember { mutableStateOf(-1L) }
    // The token whose landing scroll has actually finished. Progress is not recorded until then:
    // a new chapter's list still reports the *previous* chapter's scroll position for a frame, and
    // writing that down would move the reader's saved place to wherever the last one happened to
    // be scrolled to.
    var settledAnchorToken by remember { mutableStateOf(-1L) }

    // The overlay keeps itself on screen while a panel is open; see setControlsPinned.
    LaunchedEffect(showChapterMenu, showSettings, showBookmarks) {
        viewModel.setControlsPinned(showChapterMenu || showSettings || showBookmarks)
    }

    // The blue bars run edge to edge, so the system time/battery icons sit on blue
    // while the overlay is up: flip them to light, and put the theme's own choice
    // back on the way out.
    val view = LocalView.current
    val insetsController = remember(view) {
        view.context.findActivity()?.let { WindowCompat.getInsetsController(it.window, view) }
    }
    DisposableEffect(insetsController) {
        val controller = insetsController
        val previousStatusBars = controller?.isAppearanceLightStatusBars
        val previousNavigationBars = controller?.isAppearanceLightNavigationBars

        // Swiping the status row in while it is hidden still works, which is what this buys;
        // whether it is hidden at any moment is the overlay's business (see below).
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Everything is put back on the way out, whatever way the reader was left — the
        // on-screen 返回 button, a system back gesture, or the screen being torn down.
        onDispose {
            if (controller != null) {
                previousStatusBars?.let { controller.isAppearanceLightStatusBars = it }
                previousNavigationBars?.let { controller.isAppearanceLightNavigationBars = it }
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    // The system's status row belongs to the overlay, not to the reader: it comes up with the
    // bars — which are the colour the user picked and are what the clock is then drawn on —
    // and goes when they go, handing the whole display to the page. Reading happens on the
    // page, but a reader who has asked for the controls has asked for the status row too.
    //
    // Keyed on `showMenu` rather than on the bars themselves so the first frame stays still:
    // the row is already on screen when the reader opens, and hiding it for the frame or two
    // before the stored settings land, only to bring it back, would be a blink.
    //
    // Only the status row goes. The navigation bar stays because hiding it turns edge-to-edge
    // into full immersive mode, where the first swipe is spent bringing it back and the
    // keyboard needs either bar temporarily restored. `statusBars()` rather than
    // `systemBars()` is what keeps the two apart.
    LaunchedEffect(insetsController, state.showMenu) {
        val controller = insetsController ?: return@LaunchedEffect
        if (state.showMenu) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    // Picking a reading background is a tap the user expects to see land, so the
    // paper colour eases into the new theme instead of snapping. The surface is
    // painted from the draw phase — `drawBehind` reads the animated state, so the
    // fade redraws the background without recomposing the pager sitting on it. The
    // text colour has to follow along, otherwise dark-on-paper would stay dark for
    // the length of the fade after switching to a night theme.
    //
    // The *first* theme is the exception, and `key` is what makes it one. It arrives from
    // DataStore a frame or two after the screen does, and until it lands the state still
    // holds the stock 米黄: painting that stand-in is what turned opening the reader in dark
    // mode into a black → near-white → night flash. Before the stored settings are known the
    // surface is therefore the colour the reader was opened over (the app's own background),
    // and keying on "the settings are known" throws the animation state away the moment it
    // flips, so the first real theme starts *at* its own colour instead of animating into it.
    val palette = readerPalette(settings.theme, state.oledBlack)
    val settingsLoaded = state.settingsLoaded

    val background = key(settingsLoaded) {
        animateColorAsState(
            targetValue = if (settingsLoaded) palette.background else MaterialTheme.colorScheme.background,
            animationSpec = tween(durationMillis = Motion.SLOW_MILLIS),
            label = "readerBackground",
        )
    }
    val bodyColor = key(settingsLoaded) {
        animateColorAsState(
            targetValue = palette.text,
            animationSpec = tween(durationMillis = Motion.SLOW_MILLIS),
            label = "readerText",
        )
    }
    val pagePalette = palette.copy(text = bodyColor.value)
    val barPalette = readerBarPalette(settings, pagePalette)

    // The bars are the user's own colour, and DataStore only answers a frame or two after the
    // screen appears. Painted from the defaults in that gap they show the stock blue first and
    // then jump to the colour the user mixed — a flash in the one place the eye is already
    // looking, because the overlay is what slides in as the reader opens. So the overlay waits
    // for the settings; what it enters with is then the only colour it ever shows.
    val showBars = state.showMenu && settingsLoaded

    // The status bar the reader hides is only *gone* a few hundred ms after it is asked to go,
    // so its inset is a poor thing to lay the bar out against: taken as it comes, the title
    // would sit under the status row and then jump 138 px up the moment the inset collapses —
    // a second movement, right as the screen settles. The row's height is therefore read from
    // the inset that ignores whether it is currently shown, and applied whenever the overlay
    // is up: the title makes room for the clock as the bars arrive and takes the space back
    // when they leave, in one glide, whichever way the system's own animation is running.
    val statusBarHeight = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val barInset by animateDpAsState(
        targetValue = with(density) { (if (state.showMenu) statusBarHeight else 0).toDp() },
        animationSpec = tween(durationMillis = Motion.SLOW_MILLIS),
        label = "readerBarInset",
    )

    // The glyphs have to contrast with whatever is actually behind them: the bars while
    // the overlay is up, the page itself while it is hidden. Both are user-picked
    // colours now, so neither can be assumed any more.
    val barPrefersDarkInk = barPalette.container.prefersDarkInk()
    val pagePrefersDarkInk = pagePalette.background.prefersDarkInk()
    LaunchedEffect(insetsController, showBars, barPrefersDarkInk, pagePrefersDarkInk) {
        val controller = insetsController ?: return@LaunchedEffect
        // Wait for the bars to finish leaving before handing the status area back to
        // the page: flipping the icons while the bar is still on its way out would put
        // the wrong glyphs on it for the length of the exit.
        if (!showBars) delay(Motion.EXIT_MILLIS.toLong())
        val darkInk = if (showBars) barPrefersDarkInk else pagePrefersDarkInk
        controller.isAppearanceLightStatusBars = darkInk
        controller.isAppearanceLightNavigationBars = darkInk
    }

    // Volume-key paging. Handled on a focused wrapper rather than through a View key
    // listener so the reader state stays where it lives. Sideways, the event is
    // consumed until the chapter runs out; scrolling, the key moves a screenful and only
    // turns the chapter at the edge. Either way it falls through to the volume control when
    // there is nowhere left to go.
    val pagerScope = rememberCoroutineScope()
    val volumeFocus = remember { FocusRequester() }
    val chapterTurnThreshold = with(density) { CHAPTER_TURN_THRESHOLD.toPx() }
    LaunchedEffect(settings.volumeKeyPaging) {
        if (settings.volumeKeyPaging) {
            runCatching { volumeFocus.requestFocus() }
        }
    }

    /** Turns the chapter, or reports that it could not so the gesture can be left alone. */
    val turnChapter: (Boolean) -> Boolean = viewModel::turnChapter

    // What to say about the chapter change in flight, and about the one that just landed.
    // Tracking this here rather than in the view model keeps it with the thing it
    // describes — the reader's own transition — and makes it fire for every way a chapter
    // can change (swipe, volume key, the two buttons, the list) without any of them
    // having to remember to announce itself.
    var previousChapterIndex by remember { mutableStateOf<Int?>(null) }
    var chapterBanner by remember { mutableStateOf<ChapterBanner?>(null) }
    val move = chapterMove(previousChapterIndex, state.chapterIndex)
    val chapterCount = chapters.size

    LaunchedEffect(state.content?.chapterId) {
        val chapter = state.content ?: return@LaunchedEffect
        val index = state.chapterIndex
        val previous = previousChapterIndex
        previousChapterIndex = index
        // The chapter the reader was opened at is not a move; announcing it would be
        // telling the user about something they just asked for.
        if (previous == null) return@LaunchedEffect
        chapterBanner = ChapterBanner(
            label = when (chapterMove(previous, index)) {
                ChapterMove.FORWARD -> "下一章"
                ChapterMove.BACKWARD -> "上一章"
                ChapterMove.JUMP -> "第 ${index + 1}/$chapterCount 章"
            },
            title = chapter.title,
        )
        delay(CHAPTER_BANNER_MILLIS)
        chapterBanner = null
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(background.value) }
            .then(
                // Only the sideways reader turns a chapter with a horizontal drag. Scrolling has
                // no such gesture: the list owns vertical drags, and a chapter change it did not
                // ask for would fight the scroll.
                if (horizontal) {
                    Modifier.chapterTurnOnOverscroll(
                        pagerState = pagerState,
                        pageCount = pageCount,
                        thresholdPx = chapterTurnThreshold,
                        onTurn = { forward -> turnChapter(forward) },
                    )
                } else {
                    Modifier
                },
            )
            .focusRequester(volumeFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!settings.volumeKeyPaging) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Which key goes forward is the user's choice ("反转音量翻页"): the stock
                // mapping is volume-down forwards, and inverting it swaps the two rather
                // than changing anything else about the gesture.
                val forward = volumeKeyPageStep(
                    key = event.key,
                    inverted = settings.invertVolumeKeyPaging,
                ) ?: return@onPreviewKeyEvent false
                // A held volume key repeats every few frames. Acting on each repeat
                // would restart the page animation from a position it has not reached
                // yet, so only the first press turns a page — repeats are swallowed,
                // which also keeps them from nudging the volume. Checked after the key
                // itself, so repeats of unrelated keys are left alone.
                if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true

                if (!horizontal) {
                    val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                    val canScroll = if (forward > 0) listState.canScrollForward else listState.canScrollBackward
                    if (canScroll && viewport > 0f) {
                        val distance = if (forward > 0) viewport else -viewport
                        pagerScope.launch { listState.animateScrollBy(distance) }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent turnChapter(forward > 0)
                }

                val target = pagerState.currentPage + forward
                if (target in 0 until pageCount) {
                    pagerScope.launch { pagerState.animateScrollToPage(target) }
                    return@onPreviewKeyEvent true
                }
                // Off the end of the chapter: turn the chapter if there is one, and
                // otherwise hand the key back so it does what a volume key normally does.
                turnChapter(forward > 0)
            },
    ) {
        // ------------------------------------------------------------------ measured pagination

        // Measured here rather than in the view model because it is a layout question: how many
        // lines fit is what `TextMeasurer` answers, and the answer changes with the font size,
        // the line spacing, the padding and the window — all of which are the reader's own state.
        LaunchedEffect(content, settings, horizontal, constraints, settingsLoaded) {
            val chapter = content
            if (!horizontal || chapter == null || !settingsLoaded) {
                measured = MeasuredPages()
                return@LaunchedEffect
            }
            val width = constraints.maxWidth - 2 * horizontalPaddingPx
            val height = constraints.maxHeight - 2 * verticalPaddingPx - PAGE_HEIGHT_RESERVE_PX
            if (width <= 0 || height <= 0) {
                measured = MeasuredPages()
                return@LaunchedEffect
            }
            val laid = laidOutBlocks(chapter.blocks, lineHeightPx) { text ->
                val result = measurer.measure(
                    text = AnnotatedString(text),
                    style = pageTextStyle,
                    constraints = Constraints(maxWidth = width),
                )
                MeasuredParagraph(
                    lineEnds = (0 until result.lineCount).map { line -> result.getLineEnd(line) },
                    heightPx = result.size.height,
                )
            }
            measured = MeasuredPages(
                chapterId = chapter.chapterId,
                pages = paginateBlocks(laid, height, lineHeightPx, paragraphSpacingPx),
                ready = true,
            )
        }

        // Land on the anchor's paragraph when a chapter arrives or a jump is asked for; otherwise
        // keep the paragraph the reader was already on, so re-pagination — a font size change, a
        // rotation, a switch between the two modes — does not send them back to the top.
        LaunchedEffect(activePages, state.anchor.token, horizontal) {
            if (!horizontal || activePages.isEmpty()) return@LaunchedEffect
            val isNew = state.anchor.token != consumedAnchorToken
            consumedAnchorToken = state.anchor.token
            val target = if (isNew) state.anchor.paragraph else lastParagraph
            lastParagraph = target
            pagerState.scrollToPage(pageForParagraph(activePages, target))
            settledAnchorToken = state.anchor.token
        }

        LaunchedEffect(blockItems, state.anchor.token, horizontal, hasPreviousChapter) {
            if (horizontal || blockItems.isEmpty()) return@LaunchedEffect
            val isNew = state.anchor.token != consumedAnchorToken
            consumedAnchorToken = state.anchor.token
            val target = if (isNew) state.anchor.paragraph else lastParagraph
            lastParagraph = target
            listState.scrollToItem(
                verticalListIndex(verticalItemIndexForParagraph(blockItems, target), hasPreviousChapter),
            )
            settledAnchorToken = state.anchor.token
        }

        // Reading progress. Deliberately ignores a position equal to the one already recorded:
        // a chapter opened at paragraph 50 must not have page one's paragraph written over it
        // while the landing scroll is still on its way.
        LaunchedEffect(pagerState, activePages) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                if (settledAnchorToken != state.anchor.token) return@collect
                val paragraph = (activePages.getOrNull(page) as? ReaderPageContent.Prose)?.startParagraph
                    ?: return@collect
                if (paragraph == lastParagraph) return@collect
                lastParagraph = paragraph
                viewModel.saveProgress(paragraph)
            }
        }

        LaunchedEffect(listState, blockItems, hasPreviousChapter) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
                .filter { (_, scrolling) -> !scrolling }
                .map { (index, _) -> verticalItemIndex(index, hasPreviousChapter, blockItems.size) }
                .distinctUntilChanged()
                .collect { itemIndex ->
                    if (settledAnchorToken != state.anchor.token) return@collect
                    val paragraph = paragraphIndexAt(blockItems, itemIndex)
                    if (paragraph == lastParagraph) return@collect
                    lastParagraph = paragraph
                    viewModel.saveProgress(paragraph)
                }
        }

        when {
            state.loading -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 120.dp),
                label = when (move) {
                    ChapterMove.FORWARD -> "正在载入下一章…"
                    ChapterMove.BACKWARD -> "正在载入上一章…"
                    ChapterMove.JUMP -> "加载章节…"
                },
            )

            state.error != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyBox(
                    title = if (state.requiresLogin) "需要登录" else "章节加载失败",
                    hint = state.error,
                    actionLabel = "返回",
                    onAction = onBack,
                )
            }

            content == null -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 120.dp),
                label = "加载章节…",
            )

            // Measuring a chapter takes a moment even offline; showing the spinner beats showing
            // a page count that is not final yet.
            horizontal && !pagesReady -> LoadingBox(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 120.dp),
                label = "正在排版…",
            )

            horizontal && activePages.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyBox(title = "本章没有正文", hint = "可能为插图章节或站点未提供内容")
            }

            !horizontal && blockItems.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyBox(title = "本章没有正文", hint = "可能为插图章节或站点未提供内容")
            }

            horizontal -> HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 0.dp,
                // Compose the neighbouring page before the finger arrives: a swipe
                // then uncovers text — and already-decoded illustrations — instead
                // of a blank frame, which is most of what makes the gesture read as
                // instant.
                beyondViewportPageCount = 1,
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // A page softens as it crosses the middle of the gesture and
                        // is left untouched at rest on either side of it.
                        .pagerPageDepth {
                            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        },
                ) {
                    when (val pageContent = activePages.getOrNull(page)) {
                        is ReaderPageContent.Illustration -> IllustrationPage(
                            url = pageContent.url,
                            palette = pagePalette,
                            onTap = viewModel::toggleMenu,
                        )

                        is ReaderPageContent.Prose -> ReaderPage(
                            paragraphs = pageContent.paragraphs,
                            palette = pagePalette,
                            style = pageTextStyle,
                            settings = settings,
                            onTap = viewModel::toggleMenu,
                        )

                        null -> Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // The tap that reveals the controls. It has to be here rather than on each
                    // paragraph: a reader taps the margins and the gaps between paragraphs just as
                    // often as the glyphs, and a tap that lands on nothing at all is a control
                    // strip the user cannot get back. Scrolls still win — a tap only becomes a
                    // click if the finger does not travel.
                    .clickable(onClick = viewModel::toggleMenu),
                contentPadding = PaddingValues(
                    horizontal = settings.horizontalPaddingDp.dp,
                    vertical = READER_VERTICAL_PADDING,
                ),
                verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
            ) {
                // One continuous scroll has no edge to swipe past, so the neighbouring chapters
                // are offered as buttons at the ends of the text instead.
                if (hasPreviousChapter) {
                    item(key = "trail-previous") {
                        ChapterTrailButton(
                            label = "上一章",
                            color = pagePalette.text,
                            onClick = viewModel::previousChapter,
                        )
                    }
                }
                itemsIndexed(blockItems, key = { index, _ -> "block-$index" }) { _, item ->
                    when (item) {
                        // The illustration carries its own tap handler, which takes the tap first.
                        is VerticalItem.Prose -> ReaderParagraph(item.text, pagePalette, pageTextStyle)

                        is VerticalItem.Illustration -> IllustrationPage(
                            url = item.url,
                            palette = pagePalette,
                            onTap = viewModel::toggleMenu,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp),
                        )
                    }
                }
                if (hasNextChapter) {
                    item(key = "trail-next") {
                        ChapterTrailButton(
                            label = "下一章",
                            color = pagePalette.text,
                            onClick = viewModel::nextChapter,
                        )
                    }
                }
            }
        }

        // Names the chapter that was just entered. A swipe off the end of the previous one
        // is the only way to change chapter that the user did not ask for in words, so it
        // is the one that most needs saying — and the reader keeps showing it for the other
        // three ways too, so the feedback does not depend on how the chapter changed.
        AnimatedVisibility(
            visible = chapterBanner != null,
            enter = fadeIn(animationSpec = tween(Motion.ENTER_MILLIS)),
            exit = fadeOut(animationSpec = tween(Motion.SLOW_MILLIS)),
            modifier = Modifier.align(Alignment.Center),
            label = "chapterBanner",
        ) {
            val banner = chapterBanner
            if (banner != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = barPalette.container.copy(alpha = CHAPTER_BANNER_ALPHA),
                    border = BorderStroke(1.dp, barPalette.content.copy(alpha = 0.16f)),
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = banner.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = barPalette.content.copy(alpha = 0.7f),
                        )
                        Text(
                            text = banner.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = barPalette.content,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Tapping the page slides the controls away instead of blinking them out.
        // The bars leave faster than they arrive: an exit the eye is not meant to
        // follow, an entrance it is.
        AnimatedVisibility(
            visible = showBars,
            enter = slideInVertically(
                animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
            ) { height -> -height } + fadeIn(animationSpec = tween(Motion.ENTER_MILLIS)),
            exit = slideOutVertically(
                animationSpec = tween(Motion.EXIT_MILLIS, easing = FastOutLinearInEasing),
            ) { height -> -height } + fadeOut(animationSpec = tween(Motion.EXIT_MILLIS)),
            modifier = Modifier.align(Alignment.TopCenter),
            label = "readerTopBar",
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.content?.title ?: state.detail?.book?.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            text = "${state.chapterIndex + 1}/$chapterCount" +
                                "  ·  ${progressLabel(horizontal, pagerState.currentPage, pageCount, state.currentParagraph, content?.paragraphs?.size ?: 0)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "阅读设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barPalette.container,
                    titleContentColor = barPalette.content,
                    navigationIconContentColor = barPalette.content,
                    actionIconContentColor = barPalette.content,
                ),
                // The status row is on its way out for the whole life of this screen, so the
                // bar is laid out against an inset that is already animating away (see above)
                // rather than against the raw one, which would snap this content up mid-entry.
                windowInsets = WindowInsets(top = with(density) { barInset.roundToPx() }),
            )
        }

        AnimatedVisibility(
            visible = showBars,
            enter = slideInVertically(
                animationSpec = tween(Motion.ENTER_MILLIS, easing = LinearOutSlowInEasing),
            ) { height -> height } + fadeIn(animationSpec = tween(Motion.ENTER_MILLIS)),
            exit = slideOutVertically(
                animationSpec = tween(Motion.EXIT_MILLIS, easing = FastOutLinearInEasing),
            ) { height -> height } + fadeOut(animationSpec = tween(Motion.EXIT_MILLIS)),
            modifier = Modifier.align(Alignment.BottomCenter),
            label = "readerChapterBar",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barPalette.container),
            ) {
                // 40dp, which is Material's own minimum button height: the bar used to
                // add 8dp of padding above and below that on top of it. The buttons stay
                // an equal share of the width each, so the touch targets stay generous
                // even though the bar itself is now noticeably thinner.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(40.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = viewModel::previousChapter,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "上一章",
                            color = barPalette.content,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    // The chapter list used to be an icon in the top bar; it sits here
                    // now, next to the two turns it belongs with.
                    TextButton(
                        onClick = { showChapterMenu = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "章节",
                            color = barPalette.content,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    TextButton(
                        onClick = { showBookmarks = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "书签",
                            color = barPalette.content,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    TextButton(
                        onClick = viewModel::nextChapter,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "下一章",
                            color = barPalette.content,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showChapterMenu,
            onDismissRequest = { showChapterMenu = false },
        ) {
            val list = state.detail?.chapters.orEmpty()
            // Only the current volume's neighbourhood is listed to keep the menu usable.
            val window = list.drop((state.chapterIndex - 20).coerceAtLeast(0)).take(40)
            window.forEach { chapter ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = chapter.title,
                            maxLines = 1,
                            color = if (chapter.chapterId == state.content?.chapterId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        showChapterMenu = false
                        viewModel.openChapter(chapter.chapterId)
                    },
                )
            }
        }
    }

    if (showSettings) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState,
        ) {
            ReaderSettingsSheet(
                settings = settings,
                oledBlack = state.oledBlack,
                onFontSize = viewModel::setFontSize,
                onLineHeight = viewModel::setLineHeight,
                onParagraphSpacing = viewModel::setParagraphSpacing,
                onTheme = viewModel::setReaderTheme,
                onPageTurnMode = viewModel::setPageTurnMode,
                onToggleOled = viewModel::toggleOledBlack,
            )
        }
    }

    if (showBookmarks) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showBookmarks = false },
            sheetState = sheetState,
        ) {
            BookmarkSheet(
                canBookmark = state.canBookmark,
                bookmarks = state.bookmarks,
                currentChapterId = content?.chapterId ?: -1,
                currentParagraph = state.currentParagraph,
                // Read only while the sheet is open: asking the list where it is scrolled on
                // every frame of a scroll would recompose the reader for a panel nobody has
                // open.
                currentIsIllustration = {
                    if (horizontal) {
                        activePages.getOrNull(pagerState.currentPage) is ReaderPageContent.Illustration
                    } else {
                        val index = verticalItemIndex(
                            listState.firstVisibleItemIndex,
                            hasPreviousChapter,
                            blockItems.size,
                        )
                        blockItems.getOrNull(index) is VerticalItem.Illustration
                    }
                },
                onToggle = viewModel::toggleBookmarkAtCurrentParagraph,
                onOpen = {
                    showBookmarks = false
                    viewModel.openBookmark(it)
                },
                onDelete = viewModel::removeBookmark,
            )
        }
    }
}

/** The reader's own position line: a page count sideways, a percentage when scrolling. */
private fun progressLabel(
    horizontal: Boolean,
    currentPage: Int,
    pageCount: Int,
    currentParagraph: Int,
    paragraphCount: Int,
): String = if (horizontal) {
    if (pageCount <= 0) "排版中…" else "${currentPage + 1}/$pageCount"
} else {
    val percent = if (paragraphCount <= 0) {
        0
    } else {
        (((currentParagraph + 1) * 100) / paragraphCount).coerceIn(0, 100)
    }
    "已读 $percent%"
}

/** Turns to the previous or next chapter from the ends of the scrolling reader's text. */
@Composable
private fun ChapterTrailButton(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The local bookmarks of the book being read.
 *
 * Deliberately says *why* when the book cannot hold bookmarks rather than hiding the panel: a
 * reader who taps 书签 on an online book would otherwise have to guess that the feature exists
 * only for downloaded ones.
 */
@Composable
private fun BookmarkSheet(
    canBookmark: Boolean,
    bookmarks: List<Bookmark>,
    currentChapterId: Int,
    currentParagraph: Int,
    currentIsIllustration: () -> Boolean,
    onToggle: () -> Unit,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("书签", style = MaterialTheme.typography.titleMedium)

        if (!canBookmark) {
            Text(
                text = "本地书签只用于已整本下载的小说。在书籍详情页点「下载全本（站点打包）」后，" +
                    "这本书就可以加书签了；删除该下载时，书签会一起删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val illustration = currentIsIllustration()
        val bookmarkedHere = bookmarks.any {
            it.chapterId == currentChapterId && it.paragraphIndex == currentParagraph
        }
        Button(
            onClick = onToggle,
            enabled = !illustration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (bookmarkedHere) "取消当前位置的书签" else "为当前位置添加书签")
        }
        if (illustration) {
            Text(
                text = "插图页没有正文段落可标记，翻到正文页再加书签。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (bookmarks.isEmpty()) {
            Text(
                text = "这本书还没有书签。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@Column
        }

        Text(
            text = "共 ${bookmarks.size} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(bookmarks, key = { "${it.chapterId}-${it.paragraphIndex}" }) { bookmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(bookmark) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookmark.chapterTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = bookmark.excerpt.ifBlank { "（无摘录）" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatBookmarkTime(bookmark.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(bookmark) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除书签")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** A bookmark's timestamp, in the reader's own locale. Main thread only, like the panel itself. */
private fun formatBookmarkTime(createdAt: Long): String = if (createdAt <= 0L) {
    ""
} else {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
}

/**
 * A full-page illustration.
 *
 * Light novels put colour plates in dedicated 插图 "chapters"; those are shown
 * one image per page, fitted to the width so detail is preserved.
 */
@Composable
private fun IllustrationPage(
    url: String,
    palette: ReaderPalette,
    onTap: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val container = com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val loaded = container.imageLoader.load(url, maxWidth = ReaderViewModel.ILLUSTRATION_MAX_WIDTH)
        if (loaded != null) {
            bitmap = loaded.asImageBitmap()
        } else {
            failed = true
        }
    }

    Box(
        modifier = modifier.clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            )

            failed -> Text(
                text = "插图加载失败",
                color = palette.text,
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> CircularProgressIndicator()
        }
    }
}

/**
 * One paragraph of a page.
 *
 * [style] is the very same `TextStyle` the page was measured with (see `pageTextStyle` in
 * `ReaderScreen`): the two have to agree down to the letter spacing, or a line that measured as
 * full comes out one character too wide and spills onto the next.
 *
 * A page arrives as one entry per paragraph rather than one joined string, which is what keeps
 * `TextAlign.Justify` honest: it does not stretch a text block's last line, so a whole page drawn
 * as a single `Text` would stretch every paragraph's final line except the page's very last one.
 */
@Composable
private fun ReaderParagraph(text: String, palette: ReaderPalette, style: TextStyle) {
    Text(
        text = text,
        color = palette.text,
        style = style,
    )
}

/**
 * One page of a chapter.
 *
 * Measured to fit the screen (see `paginateBlocks`), so it does not scroll: `clipToBounds` is the
 * backstop for the one line a window shorter than a single line has to place anyway.
 */
@Composable
private fun ReaderPage(
    paragraphs: List<String>,
    palette: ReaderPalette,
    style: TextStyle,
    settings: ReaderSettings,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .clickable(onClick = onTap)
            .padding(
                horizontal = settings.horizontalPaddingDp.dp,
                vertical = READER_VERTICAL_PADDING,
            ),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        paragraphs.forEach { paragraph ->
            ReaderParagraph(paragraph, palette, style)
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
    oledBlack: Boolean,
    onFontSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onParagraphSpacing: (Int) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onPageTurnMode: (PageTurnMode) -> Unit,
    onToggleOled: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)

        Text("字号 ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.fontSizeSp,
            onValueChange = onFontSize,
            valueRange = ReaderSettings.MIN_FONT_SIZE..ReaderSettings.MAX_FONT_SIZE,
            steps = 19,
        )

        Text("行距 ${"%.1f".format(settings.lineHeightMultiplier)} 倍", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.lineHeightMultiplier,
            onValueChange = onLineHeight,
            valueRange = ReaderSettings.MIN_LINE_HEIGHT..ReaderSettings.MAX_LINE_HEIGHT,
        )

        Text("段间距 ${settings.paragraphSpacingDp} dp", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.paragraphSpacingDp.toFloat(),
            onValueChange = { onParagraphSpacing(it.toInt()) },
            valueRange = 0f..40f,
        )

        Text("背景", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { theme ->
                // The selection colour eases over rather than jumping, so the tap that
                // set it stays legible while the page behind the sheet is still fading.
                ReaderChoiceChip(
                    label = theme.label,
                    selected = theme == settings.theme,
                    onClick = { onTheme(theme) },
                    // 夜间 is the dark page, so it is where the OLED gesture lives.
                    onLongClick = if (theme == ReaderTheme.DARK) onToggleOled else null,
                    animationLabel = "readerThemeLabel",
                )
            }
        }
        Text(
            text = "长按「夜间」切换 OLED 纯黑（#000000），OLED 屏上更省电。当前：" +
                if (oledBlack) "已开启" else "未开启",
            style = MaterialTheme.typography.labelSmall,
            color = if (oledBlack) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Text("翻页方向", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageTurnMode.entries.forEach { mode ->
                ReaderChoiceChip(
                    label = mode.label,
                    selected = mode == settings.pageTurnMode,
                    onClick = { onPageTurnMode(mode) },
                    animationLabel = "readerPageTurnLabel",
                )
            }
        }

        Box(modifier = Modifier.padding(bottom = 24.dp))
    }
}

/**
 * One of the reader sheet's either/or choices, with the selection easing into place.
 *
 * A `Surface` with `combinedClickable` rather than a `TextButton`, because 夜间 carries a second,
 * hidden action (OLED pure black). A button owns its click handling, so a long press stacked on top
 * of one fires the button's click as well, in an order decided by pointer dispatch rather than by
 * this file. The 40 dp minimum height is Material's own, so the row looks unchanged.
 */
@Composable
private fun ReaderChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    animationLabel: String,
    onLongClick: (() -> Unit)? = null,
) {
    val labelColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = Motion.ENTER_MILLIS),
        label = animationLabel,
    )
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = labelColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** How far a drag has to travel past the end of a chapter before it turns the chapter. */
private val CHAPTER_TURN_THRESHOLD = 56.dp

/** How much room the page leaves above and below the text, in both reading modes. */
private val READER_VERTICAL_PADDING = 72.dp

/**
 * Safety margin under a page, covering the rounding between a measured text and a drawn one.
 *
 * The measurement is already exact about lines and font padding, so this only has to absorb
 * sub-pixel rounding — but a page that is a pixel too tall is a page that clips a descender, so
 * it is worth the eight pixels.
 */
private const val PAGE_HEIGHT_RESERVE_PX = 8

/** The line height a paragraph is drawn with, in one place so measuring and drawing agree. */
private fun readerLineHeight(settings: ReaderSettings) =
    (settings.fontSizeSp * settings.lineHeightMultiplier).sp

/**
 * Which way a volume key turns the page: `+1` forward, `-1` back, `null` for any other key.
 *
 * The stock mapping is volume-down forwards — the key under the thumb on the same edge as
 * a "next" tap — and 反转音量翻页 swaps the two. Kept out of the key handler so the mapping,
 * including the inversion, is testable without a device.
 */
internal fun volumeKeyPageStep(key: Key, inverted: Boolean): Int? = when (key) {
    Key.VolumeDown -> if (inverted) -1 else 1
    Key.VolumeUp -> if (inverted) 1 else -1
    else -> null
}

/** What the reader says about the chapter it has just moved to. */
private data class ChapterBanner(val label: String, val title: String)

/** How long the chapter banner stays up before fading. */
private const val CHAPTER_BANNER_MILLIS = 1_800L

/** Nearly opaque: the pages behind it are mid-transition and should not compete. */
private const val CHAPTER_BANNER_ALPHA = 0.94f

/**
 * Turns the chapter when a horizontal drag runs off the end of this one.
 *
 * This runs as an *ancestor* of the pager and never consumes anything, which leaves the
 * pager's own handling exactly as it was; `positionChangeIgnoreConsumed` is what keeps the
 * measurement intact even though the pager consumes the drag first. The decision itself
 * lives in [resolveChapterTurn] so it can be tested without a device.
 */
private fun Modifier.chapterTurnOnOverscroll(
    pagerState: PagerState,
    pageCount: Int,
    thresholdPx: Float,
    onTurn: (forward: Boolean) -> Unit,
): Modifier = pointerInput(pagerState, pageCount, thresholdPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startPage = pagerState.currentPage
        var horizontal = 0f
        var vertical = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.positionChangeIgnoreConsumed()
            horizontal += delta.x
            vertical += delta.y
            if (!change.pressed) break
        }

        val turn = resolveChapterTurn(
            startPage = startPage,
            pageCount = pageCount,
            pagerMoved = pagerState.currentPage != startPage,
            dragX = horizontal,
            dragY = vertical,
            thresholdPx = thresholdPx,
        )
        when (turn) {
            ChapterTurn.FORWARD -> onTurn(true)
            ChapterTurn.BACKWARD -> onTurn(false)
            ChapterTurn.NONE -> Unit
        }
    }
}

/**
 * The window behind a Compose `LocalContext`, unwrapping the theme wrappers Compose
 * puts in between.
 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ReaderPagePreview() {
    LightNovelReaderTheme {
        ReaderPage(
            paragraphs = listOf(
                "「——欢迎来到完全潜行型MMORPG『Sword Art Online』的世界。」\n" +
                    "浮在半空中的巨大岩石碎片，以及远方浮在空中的城堡。" +
                    "我抬头仰望着这片广阔无垠的碧蓝天空，深深吸了一口气。",
                "「真棒……真的就跟做梦一样呢。」",
            ),
            palette = readerPalette(ReaderTheme.PAPER),
            style = TextStyle(
                fontSize = ReaderSettings().fontSizeSp.sp,
                lineHeight = readerLineHeight(ReaderSettings()),
                textAlign = TextAlign.Justify,
            ),
            settings = ReaderSettings(),
            onTap = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ReaderSettingsSheetPreview() {
    LightNovelReaderTheme {
        ReaderSettingsSheet(
            settings = ReaderSettings(),
            oledBlack = false,
            onFontSize = {},
            onLineHeight = {},
            onParagraphSpacing = {},
            onTheme = {},
            onPageTurnMode = {},
            onToggleOled = {},
        )
    }
}

/** The same sheet with the OLED switch on, so the state line can be read in both forms. */
@Preview(name = "阅读设置 · OLED 纯黑", showBackground = true, widthDp = 360)
@Composable
private fun ReaderSettingsSheetOledPreview() {
    LightNovelReaderTheme {
        ReaderSettingsSheet(
            settings = ReaderSettings(theme = ReaderTheme.DARK),
            oledBlack = true,
            onFontSize = {},
            onLineHeight = {},
            onParagraphSpacing = {},
            onTheme = {},
            onPageTurnMode = {},
            onToggleOled = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BookmarkSheetPreview() {
    LightNovelReaderTheme {
        BookmarkSheet(
            canBookmark = true,
            bookmarks = listOf(
                Bookmark(
                    bookId = 1973,
                    chapterId = 101,
                    chapterIndex = 0,
                    chapterTitle = "第一卷 第一章 开端",
                    paragraphIndex = 3,
                    excerpt = "浮在半空中的巨大岩石碎片，以及远方浮在空中的城堡。",
                    createdAt = 1_789_225_000_000L,
                ),
            ),
            currentChapterId = 101,
            currentParagraph = 3,
            currentIsIllustration = { false },
            onToggle = {},
            onOpen = {},
            onDelete = {},
        )
    }
}

/** The same panel on a book that is not downloaded: it explains itself instead of going blank. */
@Preview(name = "书签面板 · 未整本下载", showBackground = true, widthDp = 360)
@Composable
private fun BookmarkSheetUnavailablePreview() {
    LightNovelReaderTheme {
        BookmarkSheet(
            canBookmark = false,
            bookmarks = emptyList(),
            currentChapterId = 101,
            currentParagraph = 3,
            currentIsIllustration = { false },
            onToggle = {},
            onOpen = {},
            onDelete = {},
        )
    }
}
