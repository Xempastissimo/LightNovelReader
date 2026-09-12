package com.xempastissimo.lightnovelreader.ui.screen.shelf

import com.xempastissimo.lightnovelreader.data.repo.DownloadedBook
import com.xempastissimo.lightnovelreader.data.repo.OfflineBook
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.domain.model.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where each tab's rows come from.
 *
 * The two local tabs are the ones with a decision in them: 已缓存 describes the chapter files,
 * 已下载 describes the whole-book packs, and a downloaded book must appear under exactly one of
 * them — otherwise the same book is listed twice with two different "downloaded" stories, and
 * the download looks as though it changed nothing.
 */
class ShelfFiltersTest {

    private val playBook = Book(bookId = 1, title = "示例书一", author = "作者一")
    private val warBook = Book(bookId = 2, title = "示例书二", author = "作者二")
    private val otherBook = Book(bookId = 3, title = "示例书三")

    private fun entry(
        book: Book,
        online: Boolean = false,
        progress: ReadingProgress? = null,
        addedAt: Long = 0,
    ) = ShelfEntry(book = book, online = online, progress = progress, addedAt = addedAt)

    private fun download(book: Book, at: Long) = DownloadedBook(
        bookId = book.bookId,
        book = book,
        intro = "",
        charsetName = "UTF-8",
        sourceUrl = "https://dl.example.invalid/down.php?id=${book.bookId}",
        bytes = 1_000_000,
        downloadedAt = at,
        tocTotal = 10,
        covered = 10,
        slices = emptyMap(),
        volumes = listOf(
            Volume(0, "第一卷", listOf(Chapter(1, "第一章", 0, "第一卷", 0))),
        ),
    )

    // -------------------------------------------------------------- 继续阅读 / 书架

    @Test
    fun `the reading tab shows books with progress, most recent first`() {
        val entries = listOf(
            entry(playBook, progress = ReadingProgress(bookId = 1, chapterId = 1, chapterIndex = 0, updatedAt = 100)),
            entry(warBook),
            entry(otherBook, progress = ReadingProgress(bookId = 3, chapterId = 5, chapterIndex = 4, updatedAt = 300)),
        )

        assertEquals(listOf(3, 1), shelfRows(ShelfTab.RECENT, entries, emptyMap(), emptyMap()).map { it.book.bookId })
    }

    @Test
    fun `the shelf tab shows only what the site holds`() {
        val entries = listOf(
            entry(playBook, online = true, addedAt = 1),
            entry(warBook, addedAt = 2),
            entry(otherBook, online = true, addedAt = 3),
        )

        assertEquals(listOf(3, 1), shelfRows(ShelfTab.SHELF, entries, emptyMap(), emptyMap()).map { it.book.bookId })
    }

    // ------------------------------------------------------------------------ 已缓存

    @Test
    fun `the cached tab is built from the chapter files, not from the shelf`() {
        val offline = mapOf(
            1 to OfflineBook(1, chapterCount = 3, sizeBytes = 300),
            3 to OfflineBook(3, chapterCount = 1, sizeBytes = 100),
        )

        val rows = shelfRows(ShelfTab.CACHED, listOf(entry(warBook)), offline, emptyMap())

        // A book with chapters on disk but no shelf row still gets a row, under a placeholder
        // name: an offline copy the user cannot see is one they cannot delete.
        assertEquals(listOf(1, 3), rows.map { it.book.bookId }.sorted())
        assertEquals("未知书籍 #3", rows.first { it.book.bookId == 3 }.book.title)
    }

    /** A downloaded book belongs to 已下载 only; listing it twice would misreport the download. */
    @Test
    fun `a downloaded book is not also listed as cached`() {
        val offline = mapOf(
            1 to OfflineBook(1, chapterCount = 10, sizeBytes = 1_000),
            2 to OfflineBook(2, chapterCount = 4, sizeBytes = 500),
        )
        val downloads = mapOf(1 to download(playBook, at = 1_000))

        val rows = shelfRows(ShelfTab.CACHED, listOf(entry(playBook), entry(warBook)), offline, downloads)

        assertEquals(listOf(2), rows.map { it.book.bookId })
    }

    /** A downloaded book with no shelf row must not sneak back into 已缓存 as a placeholder. */
    @Test
    fun `a downloaded book without a shelf row is not a cached orphan`() {
        val offline = mapOf(1 to OfflineBook(1, chapterCount = 10, sizeBytes = 1_000))
        val downloads = mapOf(1 to download(playBook, at = 1_000))

        assertTrue(shelfRows(ShelfTab.CACHED, emptyList(), offline, downloads).isEmpty())
    }

    // ----------------------------------------------------------------------- 已下载

    @Test
    fun `the downloaded tab lists packs newest first`() {
        val downloads = mapOf(
            1 to download(playBook, at = 1_000),
            2 to download(warBook, at = 2_000),
        )

        assertEquals(listOf(2, 1), shelfRows(ShelfTab.DOWNLOADED, emptyList(), emptyMap(), downloads).map { it.book.bookId })
    }

    /** Where a shelf row exists it is used, so the row keeps its reading progress. */
    @Test
    fun `a downloaded book keeps the reading progress of its shelf row`() {
        val progress = ReadingProgress(bookId = 1, chapterId = 9, chapterIndex = 8)
        val downloads = mapOf(1 to download(playBook, at = 1_000))

        val rows = shelfRows(ShelfTab.DOWNLOADED, listOf(entry(playBook, progress = progress)), emptyMap(), downloads)

        assertEquals(progress, rows.single().progress)
    }

    /**
     * A pack can outlive its shelf row — removing a book from the site's bookshelf deliberately
     * leaves the download alone — so the row falls back to the metadata stored with the pack
     * rather than to a placeholder.
     */
    @Test
    fun `a downloaded book without a shelf row uses the metadata stored with the pack`() {
        val downloads = mapOf(1 to download(playBook, at = 1_000))

        val row = shelfRows(ShelfTab.DOWNLOADED, emptyList(), emptyMap(), downloads).single()

        assertEquals("示例书一", row.book.title)
        assertEquals("作者一", row.book.author)
    }

    @Test
    fun `an empty download list is an empty tab`() {
        assertTrue(shelfRows(ShelfTab.DOWNLOADED, listOf(entry(playBook)), emptyMap(), emptyMap()).isEmpty())
    }
}
