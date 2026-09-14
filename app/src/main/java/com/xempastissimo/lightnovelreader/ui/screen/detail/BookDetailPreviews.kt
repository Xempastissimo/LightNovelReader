package com.xempastissimo.lightnovelreader.ui.screen.detail

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.xempastissimo.lightnovelreader.data.repo.DownloadedBook
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.domain.model.Volume
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme

/**
 * Previews for the whole detail screen.
 *
 * Screen-level previews deliberately live in their own file, away from the screen: a
 * `@Preview` drags in the tooling and a pile of fixtures that the shipping code has no other
 * use for. `BookDetailContent` is stateless exactly so that these can exist — the shipping
 * screen is the only part that needs a view model and a network.
 *
 * The states below are the point of this screen's design, and each one used to be a
 * full-page spinner:
 *  * 有种子 + 正在获取目录 — what a tap from a list looks like now;
 *  * 有种子 + 加载失败 — the header and whatever catalogue is known survive the failure;
 *  * 无种子 — the fallback after a process restart, where nothing was handed over;
 *  * 已下载 — a whole-book pack answers with its own offline catalogue.
 *
 * How to use them in Android Studio: open this file, use the **Split** or **Design** button,
 * and flip between the states in the drop-down at the top of the Preview panel.
 */
@Preview(name = "详情 · 状态", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun BookDetailStatePreview(
    @PreviewParameter(BookDetailPreviewStates::class) state: BookDetailUiState,
) {
    LightNovelReaderTheme(darkTheme = false, dynamicColor = false) {
        BookDetailContent(state = state)
    }
}

/**
 * The same screen at night, drawn with the app's own paper palette rather than the system's
 * dynamic one: the dynamic palette comes from the user's wallpaper, so a preview that used it
 * would show different colours on every machine and could not be used to judge contrast.
 */
@Preview(
    name = "详情 · 夜间",
    showBackground = true,
    widthDp = 412,
    heightDp = 1200,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BookDetailNightPreview() {
    LightNovelReaderTheme(darkTheme = true, dynamicColor = false) {
        BookDetailContent(state = BookDetailPreviewStates.loaded)
    }
}

/**
 * The states worth looking at side by side.
 *
 * Deliberately **not** private: the preview tooling resolves the provider by reflection, so a
 * private one can fail to load ("PreviewParameterProvider not found") even though it compiles.
 */
class BookDetailPreviewStates : PreviewParameterProvider<BookDetailUiState> {
    override val values: Sequence<BookDetailUiState> = sequenceOf(
        seededLoading,
        seededFailed,
        loaded,
        downloadedPack,
        withoutSeedLoading,
        withoutSeedFailed,
    )

    companion object {
        val book = Book(
            bookId = 12345,
            title = "刀剑神域",
            author = "川原砾",
            category = "轻小说",
            status = "连载中",
            latestChapter = "第 28 卷",
            updatedAt = "2026-09-01",
        )

        val detail = BookDetail(
            book = book,
            intro = "二〇二二年，人类成功制作出完全潜行型虚拟实境大型多人在线角色扮演游戏「Sword Art Online」，" +
                "但玩家们很快发现一旦登入就无法登出。Creator「茅场晶彦」宣布：只有通关全部一百层，" +
                "全体玩家才能离开这个世界——而死亡在游戏中的角色，在现实中也会死去。",
            tags = listOf("冒险", "科幻", "恋爱"),
            volumes = listOf(
                Volume(
                    volumeId = 1,
                    title = "第一卷 艾恩葛朗特",
                    chapters = listOf(
                        Chapter(1, "第 1 章 欢迎来到 MMO RPG", 1, "第一卷", 0),
                        Chapter(2, "第 2 章 封测者", 1, "第一卷", 1),
                        Chapter(3, "第 3 章 奇怪的少女", 1, "第一卷", 2),
                    ),
                ),
                Volume(
                    volumeId = 2,
                    title = "第二卷 艾恩葛朗特",
                    chapters = listOf(
                        Chapter(4, "第 4 章 红色獠牙", 2, "第二卷", 3),
                        Chapter(5, "第 5 章 铁匠", 2, "第二卷", 4),
                    ),
                ),
            ),
        )

        /** A tap from a list: the header is already drawn, the catalogue is on its way. */
        val seededLoading = BookDetailUiState(summary = book, loading = true)

        /** The same tap, with the site unreachable: the header stays, the failure is a line. */
        val seededFailed = BookDetailUiState(
            summary = book,
            loading = false,
            error = "网络请求失败，请检查网络后重试",
        )

        /** The everyday state, with reading progress and offline chapters marked. */
        val loaded = BookDetailUiState(
            summary = book,
            detail = detail,
            loading = false,
            progress = ReadingProgress(bookId = 12345, chapterId = 2, chapterIndex = 1),
            cachedChapterIds = setOf(1, 2),
            packSupported = true,
        )

        /** A downloaded book: the pack's own catalogue answers before the source does. */
        val downloadedPack = BookDetailUiState(
            summary = book,
            detail = detail,
            loading = true,
            packSupported = true,
            pack = DownloadedBook(
                bookId = 12345,
                book = book,
                intro = detail.intro,
                charsetName = "UTF-8",
                sourceUrl = "https://dl.wenku8.com/down.php?type=utf8&node=1&id=12345",
                bytes = 6_778_311,
                downloadedAt = 1_789_225_000_000,
                tocTotal = 5,
                covered = 4,
                slices = emptyMap(),
                volumes = detail.volumes,
            ),
        )

        /** After a process restart nothing was handed over, so the spinner is honest. */
        val withoutSeedLoading = BookDetailUiState(loading = true)

        /** The same, failed: with nothing to draw, the failure owns the page. */
        val withoutSeedFailed = BookDetailUiState(
            loading = false,
            error = "网络请求失败，请检查网络后重试",
        )
    }
}
