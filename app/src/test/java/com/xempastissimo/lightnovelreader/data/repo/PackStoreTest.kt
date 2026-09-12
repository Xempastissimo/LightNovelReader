package com.xempastissimo.lightnovelreader.data.repo

import com.xempastissimo.lightnovelreader.data.source.PackedBook
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8PackParser
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
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
import java.nio.charset.Charset

/**
 * Whole-book packs are two files per book: the text exactly as downloaded, and an index that
 * turns it into chapters.
 *
 * What is worth asserting here is the behaviour a restart depends on — that the index alone
 * survives being re-read, that a chapter is a byte range of the text rather than a copy of it,
 * and that a pack whose files disagree with its index is ignored instead of half-read.
 */
class PackStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File get() = File(folder.root, "packs")

    private fun store() = PackStore(root) { DOWNLOADED_AT }

    private val detail = catalog()

    /** Banner, title line, then two chapters — the shape the source publishes. */
    private fun packText(): String = listOf(
        "★☆★☆★☆轻小说文库(Www.WenKu8.Com)☆★☆★☆★",
        "<示例书名>",
        "",
        "第一卷 第一章 开端",
        "",
        "    第一段。",
        "",
        "    第二段。",
        "",
        "第一卷 第二章 转折",
        "",
        "    转折的正文。",
        "",
    ).joinToString("\n")

    private fun catalog(): BookDetail = BookDetail(
        book = Book(
            bookId = 1973,
            title = "示例书名",
            author = "示例作者",
            category = "示例文库",
            status = "连载中",
            latestChapter = "第一卷 第二章 转折",
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
                    Chapter(103, "第三章 未收录", 0, "第一卷", 2),
                ),
            ),
        ),
    )

    private fun packed(charset: Charset = Charsets.UTF_8): PackedBook {
        val bytes = packText().toByteArray(charset)
        val match = Wenku8PackParser.match(detail, Wenku8PackParser.scan(bytes, charset))
        return PackedBook(
            bytes = bytes,
            charsetName = charset.name(),
            sourceUrl = "https://dl.example.invalid/down.php?type=utf8&node=1&id=1973",
            slices = match.slices,
            coveredChapters = match.coveredChapters,
            unmatchedHeadings = match.unmatchedHeadings,
        )
    }

    private fun textFile(bookId: Int = 1973): File = File(File(root, bookId.toString()), "text.txt")

    @Test
    fun `a saved pack is listed with its metadata and its uncovered chapters`() = runTest {
        val record = store().save(packed(), detail)

        assertEquals(1973, record.bookId)
        assertEquals("示例书名", record.book.title)
        assertEquals("示例作者", record.book.author)
        assertEquals("UTF-8", record.charsetName)
        assertEquals(DOWNLOADED_AT, record.downloadedAt)
        assertEquals(3, record.tocTotal)
        assertEquals(2, record.covered)
        assertEquals(setOf(101, 102), record.chapterIds)
        assertEquals(textFile().length(), record.bytes)
        assertTrue(textFile().exists())
    }

    /** A restart re-reads the index; everything the 已下载 tab shows has to come back. */
    @Test
    fun `the index survives being read again from disk`() = runTest {
        store().save(packed(), detail)

        val reopened = PackStore(root).also { it.load() }
        val record = reopened.book(1973)

        assertNotNull(record)
        assertEquals("示例书名", record!!.book.title)
        assertEquals("示例简介。", record.intro)
        assertEquals("连载中", record.book.status)
        assertEquals(setOf(101, 102), record.chapterIds)
        assertEquals(3, record.volumes.single().chapters.size)
        assertEquals("第二章 转折", record.volumes.single().chapters[1].title)
    }

    /** Reading a chapter is seeking into the text, not decoding the whole book. */
    @Test
    fun `a chapter is read back out of the pack`() = runTest {
        val store = store().also { it.save(packed(), detail) }

        assertEquals(listOf("第一段。", "第二段。"), store.readChapter(1973, 101))
        assertEquals(listOf("转折的正文。"), store.readChapter(1973, 102))
    }

    /** A chapter the pack does not hold has no bytes to read; the caller falls back online. */
    @Test
    fun `a chapter the pack does not hold has no text`() = runTest {
        val store = store().also { it.save(packed(), detail) }

        assertNull(store.readChapter(1973, 103))
        assertNull(store.readChapter(1973, 9999))
        assertNull(store.readChapter(4242, 101))
    }

    /** A pack truncated after the download must not produce half a chapter. */
    @Test
    fun `a truncated pack yields nothing rather than a partial chapter`() = runTest {
        val store = store().also { it.save(packed(), detail) }
        val full = textFile().readBytes()
        textFile().writeBytes(full.copyOfRange(0, full.size / 3))

        assertNull(store.readChapter(1973, 102))
    }

    /** GBK is decoded with the charset recorded in the index, not guessed. */
    @Test
    fun `a GBK pack is decoded with the charset it was saved with`() = runTest {
        val store = store().also { it.save(packed(Charset.forName("GBK")), detail) }

        assertEquals("GBK", store.book(1973)!!.charsetName)
        assertEquals(listOf("第一段。", "第二段。"), store.readChapter(1973, 101))
    }

    @Test
    fun `deleting a pack removes its files and its row`() = runTest {
        val store = store().also { it.save(packed(), detail) }

        store.delete(1973)

        assertNull(store.book(1973))
        assertFalse(textFile().exists())
        assertFalse(File(root, "1973").exists())
        assertTrue(store.books.value.isEmpty())
    }

    @Test
    fun `clearing removes every pack`() = runTest {
        val store = store()
        store.save(packed(), detail)
        store.save(packed(), detail.copy(book = detail.book.copy(bookId = 3988)))

        store.clear()

        assertTrue(store.books.value.isEmpty())
        assertFalse(root.exists())
        assertEquals(0L, store.totalSizeBytes())
    }

    /**
     * A directory the app cannot describe is a directory it cannot list, open or delete per
     * book — so it is ignored rather than shown as an empty row. `clear()` is what reclaims it.
     */
    @Test
    fun `directories without a readable index are ignored`() = runTest {
        val store = store()
        store.save(packed(), detail)

        val stray = File(root, "4242").also { it.mkdirs() }
        File(stray, "text.txt").writeText("没有索引")
        val broken = File(root, "5000").also { it.mkdirs() }
        File(broken, "text.txt").writeText("x")
        File(broken, "index.json").writeText("{ 这不是 json")
        File(root, "notes").mkdirs()
        File(root, "index.json").writeText("{}")

        store.load()

        assertEquals(listOf(1973), store.books.value.map { it.bookId })
        assertEquals(textFile().length(), store.totalSizeBytes())
    }

    /** The pack's text is hex or not, but an index whose numbers are missing is unusable. */
    @Test
    fun `an index with no chapter ranges is not a downloaded book`() = runTest {
        val dir = File(root, "1973").also { it.mkdirs() }
        File(dir, "text.txt").writeText("正文")
        File(dir, "index.json").writeText(
            """{"version":1,"bookId":1973,"title":"示例书名","volumes":[
                {"id":0,"title":"第一卷","chapters":[{"id":101,"title":"第一章","index":0}]}]}""",
        )

        val store = store().also { it.load() }

        assertTrue(store.books.value.isEmpty())
    }

    /** Two packs are ordered by when they were downloaded, newest first. */
    @Test
    fun `books are listed newest first`() = runTest {
        var now = 1_000L
        val store = PackStore(root) { now }
        store.save(packed(), detail)
        now = 2_000L
        store.save(packed(), detail.copy(book = detail.book.copy(bookId = 3988)))

        assertEquals(listOf(3988, 1973), store.books.value.map { it.bookId })
    }

    /** The reader needs the catalogue after a restart, so the pack has to carry it. */
    @Test
    fun `the stored catalogue can be served as a detail`() = runTest {
        val record = store().save(packed(), detail)

        val offline = record.tocDetail()

        assertEquals(1973, offline.book.bookId)
        assertEquals("示例简介。", offline.intro)
        assertEquals(3, offline.chapters.size)
        assertEquals("第三章 未收录", offline.chapters[2].title)
    }

    /** The text stored is the text downloaded, byte for byte. */
    @Test
    fun `the pack is written verbatim`() = runTest {
        val source = packed()
        store().save(source, detail)

        assertTrue(source.bytes.contentEquals(textFile().readBytes()))
    }

    private companion object {
        const val DOWNLOADED_AT = 1_789_225_000_000L
    }
}
