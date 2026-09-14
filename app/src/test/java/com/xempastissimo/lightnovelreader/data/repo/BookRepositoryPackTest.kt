package com.xempastissimo.lightnovelreader.data.repo

import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.data.source.LoginResult
import com.xempastissimo.lightnovelreader.data.source.OnlineShelf
import com.xempastissimo.lightnovelreader.data.source.PackedBook
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8PackParser
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Bookmark
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.domain.model.SearchField
import com.xempastissimo.lightnovelreader.domain.model.UserSession
import com.xempastissimo.lightnovelreader.domain.model.Volume
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the reader is actually served, once a book has been downloaded whole.
 *
 * Three sources of text can answer for a chapter — the chapter cache, the downloaded pack, and
 * the site — and their precedence is what this asserts: the cached copy wins because it may
 * carry illustrations the pack never has, the pack answers next so a downloaded book survives
 * 清理离线章节, and the site is the last resort rather than the first.
 */
class BookRepositoryPackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = FakeSource()

    private fun repository(): BookRepository {
        val cache = ChapterCache(File(folder.root, "library"))
        val packs = PackStore(File(folder.root, "packs")) { DOWNLOADED_AT }
        return BookRepository(source, cache, packs, bookmarkStore())
    }

    /** One per repository, over the same folder, so a test can look at what a delete left behind. */
    private fun bookmarkStore() =
        BookmarkStore(File(folder.root, "library/bookmarks.json")) { DOWNLOADED_AT }

    private fun bookmark(bookId: Int, chapterId: Int) = Bookmark(
        bookId = bookId,
        chapterId = chapterId,
        chapterIndex = 0,
        chapterTitle = "第一章 开端",
        paragraphIndex = 2,
        excerpt = "第一段的开头。",
    )

    private val detail: BookDetail
        get() = BookDetail(
            book = Book(
                bookId = 1973,
                title = "示例书名",
                author = "示例作者",
                category = "示例文库",
                updatedAt = "2026-09-01",
            ),
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

    /** A pack holding both of [detail]'s chapters, as the source would publish it. */
    private fun packed(text: String = packText()): PackedBook {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val catalogue = detail
        val match = Wenku8PackParser.match(catalogue, Wenku8PackParser.scan(bytes, Charsets.UTF_8))
        return PackedBook(
            bytes = bytes,
            charsetName = "UTF-8",
            sourceUrl = "https://dl.example.invalid/down.php?type=utf8&node=1&id=1973",
            slices = match.slices,
            coveredChapters = match.coveredChapters,
            unmatchedHeadings = match.unmatchedHeadings,
        )
    }

    private fun packText(): String = listOf(
        "★☆★☆★☆轻小说文库(Www.WenKu8.Com)☆★☆★☆★",
        "<示例书名>",
        "",
        "第一卷 第一章 开端",
        "",
        "    打包里的第一段。",
        "",
        "第一卷 第二章 转折",
        "",
        "    打包里的第二段。",
        "",
    ).joinToString("\n")

    @Test
    fun `downloading a pack imports its chapters and records the download`() = runTest {
        val repository = repository()

        val record = repository.downloadPack(1973, detail)

        assertEquals(1, source.downloadCalls)
        assertEquals(2, record.covered)
        assertEquals(setOf(101, 102), repository.cachedChapterIds(1973))
        assertNotNull(repository.downloadedBook(1973))
        assertEquals(record.bytes, repository.totalPackSizeBytes())
    }

    /** The point of the feature: after the download the site is not asked for the text. */
    @Test
    fun `a downloaded chapter is served without touching the source`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)

        val content = repository.content(1973, 101, "第一章 开端")

        assertEquals(0, source.contentCalls)
        assertEquals(listOf("打包里的第一段。"), content.paragraphs)
        assertEquals("第一章 开端", content.title)
        assertEquals("第一卷", content.volumeTitle)
        assertEquals(102, content.nextChapterId)
        assertNull(content.previousChapterId)
    }

    /** The catalogue stored with the pack is what makes chapter links work offline. */
    @Test
    fun `a downloaded book keeps its chapter links`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)

        val content = repository.content(1973, 102, "第二章 转折")

        assertEquals(101, content.previousChapterId)
        assertNull(content.nextChapterId)
    }

    /**
     * 设置 → 存储 → 清理离线章节 deletes the imported chapters, and the book must stay
     * readable: the pack is the second copy, and this is what it is for.
     */
    @Test
    fun `a chapter is served from the pack after the chapter cache is cleared`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)
        repository.deleteOfflineCopy(1973)

        val content = repository.content(1973, 101, "第一章 开端")

        assertEquals(0, source.contentCalls)
        assertEquals(listOf("打包里的第一段。"), content.paragraphs)
    }

    /** The pack also keeps the offline badge honest once the chapters are gone. */
    @Test
    fun `a downloaded chapter still counts as cached`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)
        repository.deleteOfflineCopy(1973)

        assertTrue(repository.isCached(1973, 101))
        assertEquals(setOf(101, 102), repository.cachedChapterIds(1973))
    }

    /** A chapter the pack does not hold is still fetched from the site, as before. */
    @Test
    fun `a chapter outside the pack is fetched from the source`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)
        source.answer = ChapterContent(
            bookId = 1973,
            chapterId = 999,
            title = "第三卷 番外",
            blocks = listOf(ContentBlock.Paragraph("在线正文。")),
        )

        val content = repository.content(1973, 999, "番外")

        assertEquals(1, source.contentCalls)
        assertEquals(listOf("在线正文。"), content.paragraphs)
    }

    /** Once the pack is deleted, reading goes back to the network. */
    @Test
    fun `deleting the pack leaves the chapter cache intact and the source in charge`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)
        source.answer = ChapterContent(
            bookId = 1973,
            chapterId = 101,
            title = "第一章 开端",
            blocks = listOf(ContentBlock.Paragraph("在线正文。")),
        )

        repository.deletePack(1973)
        repository.deleteOfflineCopy(1973)
        val content = repository.content(1973, 101, "第一章 开端")

        assertNull(repository.downloadedBook(1973))
        assertEquals(1, source.contentCalls)
        assertEquals(listOf("在线正文。"), content.paragraphs)
    }

    /** The 已下载 tab's delete takes both copies, so the row cannot outlive the text. */
    @Test
    fun `deleting the local copy removes the pack and the chapters`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)

        repository.deleteLocalCopy(1973)

        assertNull(repository.downloadedBook(1973))
        assertTrue(repository.cachedChapterIds(1973).isEmpty())
        assertEquals(0L, repository.totalPackSizeBytes())
    }

    /**
     * A bookmark marks a *downloaded* copy, so deleting the download takes it with it.
     *
     * `deleteLocalCopy` is the one place that removes a book's local copy, which is why the
     * rule lives there: both the 已下载 tab's delete and the detail page's delete go through it,
     * and neither has to remember the bookmarks.
     */
    @Test
    fun `deleting the local copy takes that book's bookmarks and no others`() = runTest {
        val bookmarks = bookmarkStore()
        val repository = BookRepository(
            source,
            ChapterCache(File(folder.root, "library")),
            PackStore(File(folder.root, "packs")) { DOWNLOADED_AT },
            bookmarks,
        )
        repository.downloadPack(1973, detail)
        bookmarks.add(bookmark(bookId = 1973, chapterId = 101))
        bookmarks.add(bookmark(bookId = 42, chapterId = 900))

        repository.deleteLocalCopy(1973)

        assertEquals(listOf(42), bookmarks.bookmarks.value.map { it.bookId })
    }

    /**
     * 设置 → 存储 → 清理离线章节 is not the download being deleted: the pack is still there, so
     * the book is still a downloaded one and its bookmarks still have something to point at.
     */
    @Test
    fun `clearing the chapter cache leaves the bookmarks alone`() = runTest {
        val bookmarks = bookmarkStore()
        val repository = BookRepository(
            source,
            ChapterCache(File(folder.root, "library")),
            PackStore(File(folder.root, "packs")) { DOWNLOADED_AT },
            bookmarks,
        )
        repository.downloadPack(1973, detail)
        bookmarks.add(bookmark(bookId = 1973, chapterId = 101))

        repository.deleteOfflineCopy(1973)

        assertEquals(listOf(1973), bookmarks.bookmarks.value.map { it.bookId })
    }

    /** 清理整本下载 is the download going away wholesale, so the bookmarks go too. */
    @Test
    fun `clearing every pack clears the bookmarks as well`() = runTest {
        val bookmarks = bookmarkStore()
        val repository = BookRepository(
            source,
            ChapterCache(File(folder.root, "library")),
            PackStore(File(folder.root, "packs")) { DOWNLOADED_AT },
            bookmarks,
        )
        repository.downloadPack(1973, detail)
        bookmarks.add(bookmark(bookId = 1973, chapterId = 101))

        repository.deleteAllPacks()

        assertNull(repository.downloadedBook(1973))
        assertTrue(bookmarks.bookmarks.value.isEmpty())
    }

    /** The pack also carries the catalogue, which is what makes offline opening possible. */
    @Test
    fun `the detail falls back to the packs own catalogue when the source fails`() = runTest {
        val repository = repository()
        repository.downloadPack(1973, detail)
        source.detailFailure = HttpFailure.Network("没有网络")

        val offline = repository.detail(1973, forceRefresh = true)

        assertEquals("示例书名", offline.book.title)
        assertEquals(2, offline.chapters.size)
        assertEquals("示例简介。", offline.intro)
    }

    /** With no pack to fall back to, the failure is reported rather than hidden. */
    @Test
    fun `the detail failure is still reported without a pack`() = runTest {
        val repository = repository()
        source.detailFailure = HttpFailure.Network("没有网络")

        var thrown: Throwable? = null
        try {
            repository.detail(1973, forceRefresh = true)
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown is HttpFailure.Network)
    }

    /** The download button is offered only when the source publishes packs. */
    @Test
    fun `a source without packs does not claim to support them`() = runTest {
        source.supportsPacks = false
        val repository = repository()

        assertFalse(repository.supportsPackDownload)
    }

    private companion object {
        const val DOWNLOADED_AT = 1_789_225_000_000L
    }
}

/**
 * The smallest source that can answer what the repository asks of it.
 *
 * Counters are the point: several assertions are about *not* asking the site, which only a
 * source that records its calls can show.
 */
private class FakeSource : BookSource {

    override val id: String = "fake"
    override val displayName: String = "示例书源"
    override val onlineShelfCapacity: Int = 300

    var supportsPacks: Boolean = true
    var downloadCalls: Int = 0
    var contentCalls: Int = 0
    var detailFailure: Throwable? = null
    var answer: ChapterContent = ChapterContent(bookId = 0, chapterId = 0, title = "")

    override val supportsPackDownload: Boolean get() = supportsPacks

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
        detailFailure?.let { throw it }
        throw HttpFailure.Status(501, "测试没有目录")
    }

    override suspend fun chapterList(bookId: Int): BookDetail = detail(bookId)

    override suspend fun content(bookId: Int, chapterId: Int, fallbackTitle: String): ChapterContent {
        contentCalls++
        return answer.copy(bookId = bookId, chapterId = chapterId)
    }

    override suspend fun onlineShelf(): OnlineShelf = OnlineShelf()

    override suspend fun addToOnlineShelf(bookId: Int): Boolean = true

    override suspend fun removeFromOnlineShelf(bookId: Int): Boolean = true

    override suspend fun downloadPack(bookId: Int, detail: BookDetail): PackedBook {
        downloadCalls++
        val bytes = PACK.toByteArray(Charsets.UTF_8)
        val match = Wenku8PackParser.match(detail, Wenku8PackParser.scan(bytes, Charsets.UTF_8))
        return PackedBook(
            bytes = bytes,
            charsetName = "UTF-8",
            sourceUrl = "https://dl.example.invalid/down.php?type=utf8&node=1&id=$bookId",
            slices = match.slices,
            coveredChapters = match.coveredChapters,
            unmatchedHeadings = match.unmatchedHeadings,
        )
    }

    override suspend fun bookSummary(bookId: Int): Book =
        Book(bookId = bookId, title = "示例书名")

    private companion object {
        val PACK: String = listOf(
            "★☆★☆★☆轻小说文库(Www.WenKu8.Com)☆★☆★☆★",
            "<示例书名>",
            "",
            "第一卷 第一章 开端",
            "",
            "    打包里的第一段。",
            "",
            "第一卷 第二章 转折",
            "",
            "    打包里的第二段。",
            "",
        ).joinToString("\n")
    }
}
