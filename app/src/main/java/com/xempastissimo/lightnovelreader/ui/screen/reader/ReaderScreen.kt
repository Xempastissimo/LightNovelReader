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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import com.xempastissimo.lightnovelreader.data.repo.ReaderSettings
import com.xempastissimo.lightnovelreader.data.repo.ReaderTheme
import com.xempastissimo.lightnovelreader.data.repo.SettingsRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val detail: BookDetail? = null,
    val content: ChapterContent? = null,
    val pages: List<ReaderPageContent> = emptyList(),
    val chapterIndex: Int = 0,
    val initialPage: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val requiresLogin: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
    /**
     * Whether [settings] are the user's stored ones rather than the stock defaults.
     *
     * DataStore answers asynchronously, so for the first frame or two the state still holds
     * `ReaderSettings()` — whose 米黄 theme is *not* what most readers of a night theme see.
     * The screen needs to know the difference to avoid painting that stand-in (see
     * `ReaderScreen`).
     */
    val settingsLoaded: Boolean = false,
    val showMenu: Boolean = true,
)

/** One screen of the reader: either a block of prose or a full-bleed illustration. */
sealed interface ReaderPageContent {
    data class Prose(val text: String) : ReaderPageContent

    data class Illustration(val url: String) : ReaderPageContent
}

class ReaderViewModel(
    private val bookId: Int,
    private val startChapterId: Int,
    private val repository: BookRepository,
    private val shelfRepository: ShelfRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** Rough characters-per-page target; recomputed when the window size changes. */
    private var pageCapacity = DEFAULT_PAGE_CAPACITY

    /** Hides the overlay [CONTROLS_AUTO_HIDE_MILLIS] after the last interaction. */
    private val overlayTimer = OverlayAutoHideTimer(
        scope = viewModelScope,
        timeoutMillis = CONTROLS_AUTO_HIDE_MILLIS,
    ) {
        _state.update { it.copy(showMenu = false) }
    }

    init {
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { settings ->
                _state.update { current ->
                    val repaginated = if (current.content != null && settings != current.settings) {
                        paginate(current.content, settings)
                    } else {
                        current.pages
                    }
                    current.copy(settings = settings, settingsLoaded = true, pages = repaginated)
                }
            }
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

    fun openChapter(chapterId: Int) {
        val detail = _state.value.detail ?: return
        val index = detail.chapters.indexOfFirst { it.chapterId == chapterId }
        if (index < 0) return
        _state.update { it.copy(chapterIndex = index) }
        viewModelScope.launch { loadChapter(chapterId, resumeParagraph = false) }
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
    ) {
        val chapter = _state.value.detail?.chapters?.firstOrNull { it.chapterId == chapterId }
        _state.update { it.copy(loading = true, error = null, requiresLogin = false) }
        runCatching { repository.content(bookId, chapterId, chapter?.title.orEmpty()) }
            .onSuccess { content ->
                val settings = _state.value.settings
                val pages = paginate(content, settings)
                val lastPage = (pages.size - 1).coerceAtLeast(0)
                val resumePage = when {
                    resumeParagraph -> shelfRepository.progress(bookId)
                        ?.takeIf { it.chapterId == chapterId }
                        ?.paragraphIndex
                        ?.coerceIn(0, lastPage)
                        ?: 0

                    landOnLastPage -> lastPage
                    else -> 0
                }
                _state.update {
                    it.copy(
                        content = content,
                        pages = pages,
                        initialPage = resumePage,
                        loading = false,
                        error = null,
                    )
                }
                // Recorded here rather than left to the pager's page listener. Opening a
                // chapter lands on a page the pager may already be showing — page 0 of a
                // chapter read after one that also ended on page 0 — and a page that does
                // not change produces nothing for that listener to observe, which would
                // leave the stored position pointing at the chapter just left.
                saveProgress(resumePage)
            }
            .onFailure { error ->
                val requiresLogin =
                    error is com.xempastissimo.lightnovelreader.data.network.HttpFailure.AuthRequired
                _state.update {
                    it.copy(loading = false, error = error.toUserMessage(), requiresLogin = requiresLogin)
                }
            }
    }

    /** Re-paginates for the current window; called when the reader is measured. */
    fun updatePageCapacity(capacity: Int) {
        if (capacity <= 0 || capacity == pageCapacity) return
        pageCapacity = capacity
        val content = _state.value.content ?: return
        _state.update { it.copy(pages = paginate(content, it.settings)) }
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
     * Keeps the overlay on screen while a panel (settings sheet, chapter list) is
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

    fun setReaderTheme(theme: com.xempastissimo.lightnovelreader.data.repo.ReaderTheme) =
        viewModelScope.launch { settingsRepository.setReaderTheme(theme) }

    /** Stores the reading position; called as pages change. */
    fun saveProgress(page: Int) {
        val detail = _state.value.detail ?: return
        val content = _state.value.content ?: return
        val chapter = detail.chapters.getOrNull(_state.value.chapterIndex) ?: return
        val percent = if (_state.value.pages.isEmpty()) {
            0f
        } else {
            (page + 1).toFloat() / _state.value.pages.size
        }
        viewModelScope.launch {
            shelfRepository.add(detail.book)
            shelfRepository.saveProgress(
                ReadingProgress(
                    bookId = bookId,
                    chapterId = chapter.chapterId,
                    chapterIndex = chapter.index,
                    paragraphIndex = page,
                    percentInChapter = percent,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Splits a chapter into pages.
     *
     * A character budget is used instead of real text measurement: it is
     * deterministic, needs no layout pass, and keeps the reading position stable
     * across re-pagination. Illustrations become pages of their own, which is how
     * 插图 chapters are meant to be read. Swapping in `TextMeasurer`-based
     * pagination later only touches this method.
     */
    private fun paginate(content: ChapterContent, settings: ReaderSettings): List<ReaderPageContent> {
        if (content.blocks.isEmpty()) return emptyList()

        val paragraphs = content.paragraphs
        if (paragraphs.isEmpty() && content.illustrations.isEmpty()) {
            return listOf(ReaderPageContent.Prose("（本章没有正文）"))
        }

        val pages = ArrayList<ReaderPageContent>(paragraphs.size / 4 + 1)
        val builder = StringBuilder()
        var used = 0
        val capacity = pageCapacity

        fun flush() {
            if (builder.isNotEmpty()) {
                pages.add(ReaderPageContent.Prose(builder.toString()))
                builder.setLength(0)
                used = 0
            }
        }

        for (block in content.blocks) {
            when (block) {
                is ContentBlock.Illustration -> {
                    flush()
                    pages.add(ReaderPageContent.Illustration(block.url))
                }

                is ContentBlock.Paragraph -> {
                    val paragraph = block.text
                    if (paragraph.isBlank()) continue
                    if (used > 0 && used + paragraph.length > capacity) flush()
                    if (paragraph.length > capacity) {
                        // A single paragraph longer than a page: hard split.
                        var rest = paragraph
                        while (rest.length > capacity) {
                            pages.add(ReaderPageContent.Prose(rest.take(capacity)))
                            rest = rest.drop(capacity)
                        }
                        if (rest.isNotEmpty()) {
                            builder.append(rest)
                            used = rest.length
                        }
                        continue
                    }
                    if (builder.isNotEmpty()) {
                        builder.append('\n')
                        used++
                    }
                    builder.append(paragraph)
                    used += paragraph.length
                }
            }
        }
        flush()
        return pages
    }

    companion object {
        const val DEFAULT_PAGE_CAPACITY = 520

        /** How long the overlay (title bar + chapter bar) stays up after a tap. */
        const val CONTROLS_AUTO_HIDE_MILLIS = 5_000L

        fun factory(container: AppContainer, bookId: Int, chapterId: Int) =
            AppViewModelFactory<ReaderViewModel> {
                ReaderViewModel(
                    bookId = bookId,
                    startChapterId = chapterId,
                    repository = it.bookRepository,
                    shelfRepository = it.shelfRepository,
                    settingsRepository = it.settingsRepository,
                )
            }
    }
}

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

    val pageCount = state.pages.size
    val pagerState = rememberPagerState(
        initialPage = state.initialPage.coerceAtLeast(0),
        pageCount = { pageCount },
    )

    // Jump to the stored page whenever a new chapter arrives.
    LaunchedEffect(state.content?.chapterId, pageCount) {
        if (pageCount > 0) {
            pagerState.scrollToPage(state.initialPage.coerceIn(0, pageCount - 1))
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.saveProgress(page)
        }
    }

    // The overlay keeps itself on screen while a panel is open; see setControlsPinned.
    LaunchedEffect(showChapterMenu, showSettings) {
        viewModel.setControlsPinned(showChapterMenu || showSettings)
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
    // mode into a black → near-white → night flash — the page appeared on cream and then
    // eased across to the background the user had actually chosen. Before the stored
    // settings are known the surface is therefore the colour the reader was opened over (the
    // app's own background), and keying on "the settings are known" throws the animation
    // state away the moment it flips, so the first real theme starts *at* its own colour
    // instead of animating into it. Only a theme picked after that is worth watching.
    val palette = readerPalette(state.settings.theme)
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
    val barPalette = readerBarPalette(state.settings, pagePalette)

    // The bars are the user's own colour, and DataStore only answers a frame or two after the
    // screen appears. Painted from the defaults in that gap they show the stock blue first and
    // then jump to the colour the user mixed — a flash in the one place the eye is already
    // looking, because the overlay is what slides in as the reader opens. So the overlay waits
    // for the settings; what it enters with is then the only colour it ever shows. (The page
    // itself is held back the same way, for the same reason; see below.)
    val showBars = state.showMenu && state.settingsLoaded

    // The status bar the reader hides is only *gone* a few hundred ms after it is asked to go,
    // so its inset is a poor thing to lay the bar out against: taken as it comes, the title
    // would sit under the status row and then jump 138 px up the moment the inset collapses —
    // a second movement, right as the screen settles. The row's height is therefore read from
    // the inset that ignores whether it is currently shown, and applied whenever the overlay
    // is up: the title makes room for the clock as the bars arrive and takes the space back
    // when they leave, in one glide, whichever way the system's own animation is running.
    val density = LocalDensity.current
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
    // listener so the pager state stays where it lives. Inside a chapter the event is
    // consumed; past either end it turns the chapter if there is one, and only falls
    // through to the volume control when there is not.
    val pagerScope = rememberCoroutineScope()
    val volumeFocus = remember { FocusRequester() }
    val chapterTurnThreshold = with(LocalDensity.current) { CHAPTER_TURN_THRESHOLD.toPx() }
    LaunchedEffect(state.settings.volumeKeyPaging) {
        if (state.settings.volumeKeyPaging) {
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
    val chapterCount = state.detail?.chapters?.size ?: 0

    LaunchedEffect(state.content?.chapterId) {
        val content = state.content ?: return@LaunchedEffect
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
            title = content.title,
        )
        delay(CHAPTER_BANNER_MILLIS)
        chapterBanner = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(background.value) }
            .chapterTurnOnOverscroll(
                pagerState = pagerState,
                pageCount = pageCount,
                thresholdPx = chapterTurnThreshold,
                onTurn = { forward -> turnChapter(forward) },
            )
            .focusRequester(volumeFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!state.settings.volumeKeyPaging) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Which key goes forward is the user's choice ("反转音量翻页"): the stock
                // mapping is volume-down forwards, and inverting it swaps the two rather
                // than changing anything else about the gesture.
                val forward = volumeKeyPageStep(
                    key = event.key,
                    inverted = state.settings.invertVolumeKeyPaging,
                ) ?: return@onPreviewKeyEvent false
                // A held volume key repeats every few frames. Acting on each repeat
                // would restart the page animation from a position it has not reached
                // yet, so only the first press turns a page — repeats are swallowed,
                // which also keeps them from nudging the volume. Checked after the key
                // itself, so repeats of unrelated keys are left alone.
                if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
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

            pageCount == 0 -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyBox(title = "本章没有正文", hint = "可能为插图章节或站点未提供内容")
            }

            else -> {
                HorizontalPager(
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
                        when (val pageContent = state.pages.getOrNull(page)) {
                            is ReaderPageContent.Illustration -> IllustrationPage(
                                url = pageContent.url,
                                palette = pagePalette,
                                onTap = viewModel::toggleMenu,
                            )

                            is ReaderPageContent.Prose -> ReaderPage(
                                text = pageContent.text,
                                palette = pagePalette,
                                settings = state.settings,
                                onTap = viewModel::toggleMenu,
                            )

                            null -> Box(modifier = Modifier.fillMaxSize())
                        }
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
                            text = "${state.chapterIndex + 1}/${state.detail?.chapters?.size ?: 0}" +
                                "  ·  ${pagerState.currentPage + 1}/$pageCount",
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
                // a third of the screen wide each, so the touch targets stay generous
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
            val chapters = state.detail?.chapters.orEmpty()
            // Only the current volume's neighbourhood is listed to keep the menu usable.
            val window = chapters.drop((state.chapterIndex - 20).coerceAtLeast(0)).take(40)
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
                settings = state.settings,
                onFontSize = viewModel::setFontSize,
                onLineHeight = viewModel::setLineHeight,
                onParagraphSpacing = viewModel::setParagraphSpacing,
                onTheme = viewModel::setReaderTheme,
            )
        }
    }
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
) {
    val container = com.xempastissimo.lightnovelreader.ui.LocalAppContainer.current
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val loaded = container.imageLoader.load(url, maxWidth = 1080)
        if (loaded != null) {
            bitmap = loaded.asImageBitmap()
        } else {
            failed = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onTap),
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
 * One page of a chapter.
 *
 * Tapping anywhere toggles the controls; the page content also scrolls, so a
 * paragraph taller than the screen stays reachable on small displays.
 *
 * The page arrives as one string with its paragraphs separated by `\n` (see
 * `ReaderViewModel.paginate`) and is rendered as one `Text` per paragraph, spaced by
 * [ReaderSettings.paragraphSpacingDp]. That setting used to be stored and edited but
 * never read here, which is why moving the slider changed nothing on the page.
 *
 * It also fixes justification: `TextAlign.Justify` does not stretch the last line of a
 * text block, so a whole page of paragraphs in a single `Text` had every paragraph's
 * final line stretched except the page's very last one.
 */
@Composable
private fun ReaderPage(
    text: String,
    palette: ReaderPalette,
    settings: ReaderSettings,
    onTap: () -> Unit,
) {
    val paragraphs = remember(text) { text.split('\n').filter { it.isNotBlank() } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onTap)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = settings.horizontalPaddingDp.dp,
                vertical = 72.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        paragraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                color = palette.text,
                fontSize = settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                textAlign = TextAlign.Justify,
            )
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onFontSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onParagraphSpacing: (Int) -> Unit,
    onTheme: (com.xempastissimo.lightnovelreader.data.repo.ReaderTheme) -> Unit,
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
            com.xempastissimo.lightnovelreader.data.repo.ReaderTheme.entries.forEach { theme ->
                // The selection colour eases over rather than jumping, so the tap that
                // set it stays legible while the page behind the sheet is still fading.
                val labelColor by animateColorAsState(
                    targetValue = if (theme == settings.theme) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    animationSpec = tween(durationMillis = Motion.ENTER_MILLIS),
                    label = "readerThemeLabel",
                )
                TextButton(onClick = { onTheme(theme) }) {
                    Text(text = theme.label, color = labelColor)
                }
            }
        }
        Box(modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** How far a drag has to travel past the end of a chapter before it turns the chapter. */
private val CHAPTER_TURN_THRESHOLD = 56.dp

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
            text = "「——欢迎来到完全潜行型MMORPG『Sword Art Online』的世界。」\n" +
                "浮在半空中的巨大岩石碎片，以及远方浮在空中的城堡。" +
                "我抬头仰望着这片广阔无垠的碧蓝天空，深深吸了一口气。\n" +
                "「真棒……真的就跟做梦一样呢。」",
            palette = readerPalette(ReaderTheme.PAPER),
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
            onFontSize = {},
            onLineHeight = {},
            onParagraphSpacing = {},
            onTheme = {},
        )
    }
}
