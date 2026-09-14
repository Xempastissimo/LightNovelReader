package com.xempastissimo.lightnovelreader.data.repo

import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.data.source.LoginResult
import com.xempastissimo.lightnovelreader.data.source.OnlineShelf
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.domain.model.SearchField
import com.xempastissimo.lightnovelreader.domain.model.UserSession
import com.xempastissimo.lightnovelreader.domain.model.Volume
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The list row handed to the detail screen, and what it must *not* be allowed to do.
 *
 * The hand-off exists so a book's page can draw its cover, title and 文库 before the source has
 * answered. The trap it must not fall into is standing in for the source: a book opened from a
 * list still has to have its own page read, or it would never pick up a chapter tree.
 */
class BookRepositorySummaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = FakeSummarySource()

    /** Built per test, because `TemporaryFolder` does not exist while the fields are set up. */
    private fun repository(): BookRepository = BookRepository(
        source,
        ChapterCache(File(folder.root, "library")),
        PackStore(File(folder.root, "packs")) { 1_789_225_000_000L },
    )

    @Test
    fun `a remembered row is handed back for the header`() {
        val repository = repository()
        val book = Book(bookId = 1973, title = "示例书名", author = "示例作者", category = "示例文库")

        repository.rememberSummary(book)

        assertEquals(book, repository.summary(1973))
        assertNull(repository.summary(42))
    }

    @Test
    fun `a remembered row never stands in for the site's own page`() = runTest {
        val repository = repository()
        repository.rememberSummary(Book(bookId = 1973, title = "只有标题"))

        val detail = repository.detail(1973)

        assertEquals(2, detail.chapters.size)
        assertEquals(1, source.detailCalls)
    }

    /** Revisiting a book whose page was already read needs no hand-off at all. */
    @Test
    fun `a detail already read answers for the header`() = runTest {
        val repository = repository()

        repository.detail(1973)

        assertEquals("示例书名", repository.summary(1973)?.title)
    }

    @Test
    fun `only the most recent rows are remembered`() {
        val repository = repository()
        repeat(40) { index ->
            repository.rememberSummary(Book(bookId = index + 1, title = "书 ${index + 1}"))
        }

        assertNull(repository.summary(1))
        assertEquals("书 40", repository.summary(40)?.title)
    }

    /** The map is a short history, not a queue: a row tapped again is not the next one out. */
    @Test
    fun `re-remembering a row keeps it alive`() {
        val repository = repository()
        repository.rememberSummary(Book(bookId = 1, title = "第一本"))
        repeat(31) { index ->
            repository.rememberSummary(Book(bookId = index + 2, title = "书 ${index + 2}"))
        }

        repository.rememberSummary(Book(bookId = 1, title = "第一本"))
        repository.rememberSummary(Book(bookId = 100, title = "新书"))

        assertEquals("第一本", repository.summary(1)?.title)
        assertNull(repository.summary(2))
    }
}

/** The smallest source that can answer what the summary hand-off asks of it. */
private class FakeSummarySource : BookSource {

    var detailCalls: Int = 0

    override val id: String = "fake"
    override val displayName: String = "示例书源"
    override val onlineShelfCapacity: Int = 300

    override fun isLoggedIn(): Boolean = true

    override fun currentSession(): UserSession? = UserSession(userName = "tester")

    override fun imageReferer(): String = "https://example.invalid/"

    override suspend fun login(userName: String, password: String, keepDays: Int): LoginResult =
        LoginResult.Failure("不支持")

    override fun logout() = Unit

    override suspend fun rank(type: RankType, page: Int): List<Book> = emptyList()

    override suspend fun catalog(page: Int, fullOnly: Boolean): List<Book> = emptyList()

    override suspend fun search(keyword: String, field: SearchField): List<Book> = emptyList()

    override suspend fun recentUpdates(): List<Book> = emptyList()

    override suspend fun detail(bookId: Int): BookDetail {
        detailCalls++
        return BookDetail(
            book = Book(bookId = bookId, title = "示例书名", author = "示例作者", category = "示例文库"),
            intro = "示例简介。",
            volumes = listOf(
                Volume(
                    volumeId = 0,
                    title = "第一卷",
                    chapters = listOf(
                        Chapter(101, "第一章 开端", 0, "第一卷", 0),
                        Chapter(102, "第二章 转折", 0, "第一卷", 1),
                    ),
                ),
            ),
        )
    }

    override suspend fun chapterList(bookId: Int): BookDetail = detail(bookId)

    override suspend fun content(bookId: Int, chapterId: Int, fallbackTitle: String): ChapterContent =
        ChapterContent(bookId = bookId, chapterId = chapterId, title = fallbackTitle)

    override suspend fun onlineShelf(): OnlineShelf = OnlineShelf()

    override suspend fun addToOnlineShelf(bookId: Int): Boolean = true

    override suspend fun removeFromOnlineShelf(bookId: Int): Boolean = true
}
