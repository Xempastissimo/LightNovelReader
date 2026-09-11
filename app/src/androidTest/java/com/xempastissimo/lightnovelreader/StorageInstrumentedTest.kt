package com.xempastissimo.lightnovelreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xempastissimo.lightnovelreader.data.repo.ChapterCache
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.core.json.Json
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device checks for the pieces that depend on the Android framework:
 * file-backed storage, JSON codec integration and cache bookkeeping.
 *
 * These complement the JVM unit tests (which cover parsing and the network
 * layer) and run without a live book source, so they stay valid even when the
 * site is unreachable.
 */
@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tempDir(name: String): File =
        File(context.cacheDir, "test-$name-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun chapterCacheRoundTrip() = runBlocking {
        val cache = ChapterCache(tempDir("chapters"))
        val content = ChapterContent(
            bookId = 1973,
            chapterId = 176596,
            title = "第一章",
            volumeTitle = "第一卷",
            blocks = listOf(
                ContentBlock.Paragraph("第一段"),
                ContentBlock.Illustration("https://example.invalid/a.jpg"),
                ContentBlock.Paragraph("第二段"),
            ),
            previousChapterId = 176595,
            nextChapterId = 176597,
        )

        assertFalse(cache.isCached(1973, 176596))
        cache.write(content)
        assertTrue(cache.isCached(1973, 176596))
        assertEquals(setOf(176596), cache.cachedChapterIds(1973))

        val restored = cache.read(1973, 176596)
        assertNotNull(restored)
        restored!!
        assertEquals("第一章", restored.title)
        assertEquals(listOf("第一段", "第二段"), restored.paragraphs)
        assertEquals(listOf("https://example.invalid/a.jpg"), restored.illustrations)
        assertEquals(176595, restored.previousChapterId)
        assertEquals(176597, restored.nextChapterId)
        assertTrue(cache.bookSizeBytes(1973) > 0)

        cache.deleteBook(1973)
        assertFalse(cache.isCached(1973, 176596))
    }

    @Test
    fun shelfRepositoryPersistsEntriesAndProgress() = runBlocking {
        val file = File(tempDir("shelf"), "shelf.json")
        val repository = ShelfRepository(file)
        repository.load()
        assertTrue(repository.entries.value.isEmpty())

        val book = Book(bookId = 1973, title = "测试书", author = "作者", category = "文库")
        repository.add(book)
        repository.add(book) // adding twice must not duplicate
        assertEquals(1, repository.entries.value.size)
        assertTrue(repository.isOnShelf(1973))

        repository.saveProgress(
            ReadingProgress(
                bookId = 1973,
                chapterId = 176596,
                chapterIndex = 3,
                paragraphIndex = 5,
                percentInChapter = 0.5f,
                updatedAt = 1_700_000_000_000L,
            ),
        )
        repository.setCachedChapters(1973, setOf(1, 2, 3))
        repository.recordSearch("实力至上")

        // A fresh instance must read everything back from disk.
        val reloaded = ShelfRepository(file)
        reloaded.load()
        val entry = reloaded.entry(1973)
        assertNotNull(entry)
        assertEquals("测试书", entry!!.book.title)
        assertEquals(176596, entry.progress?.chapterId)
        assertEquals(3, entry.progress?.chapterIndex)
        assertEquals(setOf(1, 2, 3), entry.cachedChapterIds)
        assertTrue(entry.hasOfflineCopy)
        assertEquals(listOf("实力至上"), reloaded.searchHistory.value)

        reloaded.remove(1973)
        assertFalse(reloaded.isOnShelf(1973))
    }

    @Test
    fun shelfRepositorySurvivesCorruptStorage() = runBlocking {
        val file = File(tempDir("corrupt"), "shelf.json")
        file.writeText("{ this is not json")
        val repository = ShelfRepository(file)
        repository.load()
        // A broken file must degrade to an empty library, never crash.
        assertTrue(repository.entries.value.isEmpty())
        repository.add(Book(bookId = 1, title = "恢复"))
        assertEquals(1, repository.entries.value.size)
    }

    @Test
    fun onlineEntriesMergeWithLocalOnes() = runBlocking {
        val file = File(tempDir("merge"), "shelf.json")
        val repository = ShelfRepository(file)
        repository.load()
        repository.add(Book(bookId = 1, title = "本地书"))
        repository.replaceOnlineEntries(listOf(Book(bookId = 2, title = "在线书")))
        assertEquals(setOf(1, 2), repository.entries.value.map { it.book.bookId }.toSet())
        assertEquals(1, repository.entries.value.count { it.online })

        repository.replaceOnlineEntries(emptyList())
        assertEquals(listOf(1), repository.entries.value.map { it.book.bookId })
    }

    @Test
    fun jsonCodecHandlesNestedDocuments() {
        val document = Json.obj {
            put("title", "书名")
            put("count", 3)
            put("ratio", 0.5)
            put("flag", true)
            put("nested", Json.obj { put("inner", "值") })
            put("list", Json.array { add("a"); add("b") })
        }.toJson()

        val parsed = Json.parseObject(document)!!
        assertEquals("书名", parsed.string("title"))
        assertEquals(3, parsed.int("count"))
        assertEquals(0.5, parsed.double("ratio"), 0.0001)
        assertTrue(parsed.boolean("flag"))
        val nested = parsed.entries["nested"] as Json.Obj
        assertEquals("值", nested.string("inner"))
        assertEquals(listOf("a", "b"), parsed.strings("list"))
    }
}
