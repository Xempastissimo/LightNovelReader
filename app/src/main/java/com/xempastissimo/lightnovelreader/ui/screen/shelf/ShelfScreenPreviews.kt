package com.xempastissimo.lightnovelreader.ui.screen.shelf

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.xempastissimo.lightnovelreader.data.repo.DownloadedBook
import com.xempastissimo.lightnovelreader.data.repo.OfflineBook
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.domain.model.Volume
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme

/**
 * Previews for the whole shelf screen.
 *
 * Screen-level previews live in their own file, away from the screen: a `@Preview` drags in
 * the tooling and a pile of fixtures the shipping code has no use for, and keeping them
 * apart is what lets `ShelfScreen.kt` read as just the screen. `ShelfContent` is stateless
 * exactly so these can exist — the shipping half is the only part that needs a view model.
 *
 * In Android Studio: open this file, use **Split** or **Design**, and flip between the
 * states with the drop-down at the top of the panel. The night preview below is the no-code
 * way to see the same rows in the dark palette.
 */
@Preview(name = "书架 · 各页签", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun ShelfScreenPreview(
    @PreviewParameter(ShelfPreviewStates::class) state: ShelfUiState,
) {
    LightNovelReaderTheme(darkTheme = false, dynamicColor = false) {
        ShelfContent(state = state)
    }
}

/**
 * The shelf at night, drawn with the app's own paper palette rather than the system's
 * dynamic one: a dynamic palette comes from the user's wallpaper, so a preview that used it
 * would look different on every machine and could not be used to judge contrast.
 */
@Preview(
    name = "书架 · 已下载 · 夜间",
    showBackground = true,
    widthDp = 412,
    heightDp = 900,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ShelfScreenNightPreview() {
    LightNovelReaderTheme(darkTheme = true, dynamicColor = false) {
        ShelfContent(state = downloaded)
    }
}

/**
 * The shelf's states, one per interesting answer.
 *
 * Not `private`: the preview tool resolves the provider by reflection, and a private class
 * fails to load in the panel even though it compiles.
 */
class ShelfPreviewStates : PreviewParameterProvider<ShelfUiState> {
    override val values: Sequence<ShelfUiState> = sequenceOf(
        recent,
        shelfLoggedIn,
        shelfLoggedOut,
        cached,
        downloaded,
        downloadedEmpty,
    )
}

private val sampleBooks: List<Book> = listOf(
    Book(
        bookId = 3988,
        title = "玩玩的恋爱关系(玩乐关系)",
        author = "葵关南",
        category = "富士见文库",
        latestChapter = "第四卷 蜜瓜特典 朋友以上的证明",
        updatedAt = "2026-09-09",
    ),
    Book(
        bookId = 2231,
        title = "86-不存在的战区-(86- Eighty Six -)",
        author = "安里アサト",
        category = "电击文库",
        latestChapter = "第十二卷 Holy blue bullet 终章",
        updatedAt = "2026-08-16",
    ),
    Book(
        bookId = 4265,
        title = "欢迎来到实力至上主义的教室",
        author = "衣笠彰梧",
        category = "MF文库J",
        latestChapter = "第二十七卷 三年级篇 4",
        updatedAt = "2026-06-26",
    ),
    Book(
        bookId = 3475,
        title = "如果是恋，是爱，是温柔",
        author = "一穗ミチ",
        category = "小学馆",
        latestChapter = "第一卷 插图",
        updatedAt = "2026-09-10",
    ),
)

private fun sampleVolumes(bookId: Int): List<Volume> = listOf(
    Volume(
        volumeId = 0,
        title = "第一卷",
        chapters = listOf(
            Chapter(1, "序章 在战场上绽放的红色虞美人", 0, "第一卷", 0),
            Chapter(2, "第一章 阵亡者为零的战场", 0, "第一卷", 1),
            Chapter(3, "第二章 白骨战线无战事", 0, "第一卷", 2),
        ),
    ),
)

private fun sampleDownload(
    book: Book,
    covered: Int = 12,
    tocTotal: Int = 12,
    bytes: Long = 1_715_816,
    downloadedAt: Long = 1_789_225_000_000,
): DownloadedBook = DownloadedBook(
    bookId = book.bookId,
    book = book,
    intro = "样例简介，仅用于预览。",
    charsetName = "UTF-8",
    sourceUrl = "https://dl.wenku8.com/down.php?type=utf8&node=1&id=${book.bookId}",
    bytes = bytes,
    downloadedAt = downloadedAt,
    tocTotal = tocTotal,
    covered = covered,
    slices = emptyMap(),
    volumes = sampleVolumes(book.bookId),
)

private val recent = ShelfUiState(
    tab = ShelfTab.RECENT,
    entries = listOf(
        ShelfEntry(
            book = sampleBooks[0],
            progress = ReadingProgress(bookId = 3988, chapterId = 81174, chapterIndex = 5, updatedAt = 1_789_300_000_000),
        ),
        ShelfEntry(
            book = sampleBooks[1],
            progress = ReadingProgress(bookId = 2231, chapterId = 81175, chapterIndex = 7, updatedAt = 1_789_200_000_000),
        ),
    ),
    allEntries = emptyList(),
)

private val shelfLoggedIn = ShelfUiState(
    tab = ShelfTab.SHELF,
    loggedIn = true,
    onlineCapacity = 300,
    siteTotalCount = 3,
    entries = sampleBooks.mapIndexed { index, book -> ShelfEntry(book = book, addedAt = 3L - index, online = true) },
)

private val shelfLoggedOut = ShelfUiState(tab = ShelfTab.SHELF, loggedIn = false)

private val cached = ShelfUiState(
    tab = ShelfTab.CACHED,
    entries = listOf(
        ShelfEntry(book = sampleBooks[2], progress = ReadingProgress(bookId = 4265, chapterId = 900, chapterIndex = 7)),
        ShelfEntry(book = sampleBooks[3]),
    ),
    allEntries = emptyList(),
    offline = mapOf(
        4265 to OfflineBook(bookId = 4265, chapterCount = 1, sizeBytes = 56_000),
        3475 to OfflineBook(bookId = 3475, chapterCount = 6, sizeBytes = 270_000),
    ),
)

private val downloaded = ShelfUiState(
    tab = ShelfTab.DOWNLOADED,
    entries = listOf(
        ShelfEntry(
            book = sampleBooks[0],
            progress = ReadingProgress(bookId = 3988, chapterId = 81174, chapterIndex = 5),
        ),
        ShelfEntry(book = sampleBooks[1]),
    ),
    allEntries = emptyList(),
    downloads = mapOf(
        3988 to sampleDownload(sampleBooks[0], covered = 45, tocTotal = 45),
        2231 to sampleDownload(
            sampleBooks[1],
            covered = 262,
            tocTotal = 270,
            bytes = 6_778_311,
            downloadedAt = 1_789_100_000_000,
        ),
    ),
)

private val downloadedEmpty = ShelfUiState(tab = ShelfTab.DOWNLOADED)
