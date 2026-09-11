package com.xempastissimo.lightnovelreader.data.repo

import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ShelfRepository] holds two different things in one list: the mirror of the source's
 * own bookshelf, and this app's reading history. `replaceOnlineEntries` has to reconcile
 * the incoming mirror with what is already there — without duplicating a book that is
 * both, and without dropping local rows the site knows nothing about (reading progress
 * lives on those).
 *
 * The 书架 tab then shows only the `online` rows, and 继续阅读 shows the rest. These
 * tests cover the repository half of that contract.
 *
 * `ShelfRepository` is deliberately JVM-testable (a plain JSON file, the project's own
 * codec), so these run as local unit tests.
 */
class ShelfRepositoryMergeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository() = ShelfRepository(File(folder.root, "shelf.json"))

    /** Mirrors the ordering the shelf screen uses for the merged tab. */
    private fun ShelfRepository.mergedShelf() =
        entries.value.sortedByDescending { it.addedAt }.map { it.book.bookId }

    @Test
    fun onlineEntrySubsumesTheLocalCopyOfTheSameBook() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 7, title = "本地收藏"))

        // The same book also comes back from the source's bookshelf.
        repository.replaceOnlineEntries(listOf(Book(bookId = 7, title = "本地收藏")))

        // One row, not two: they are the same favourite.
        assertEquals(listOf(7), repository.entries.value.map { it.book.bookId })
        assertTrue(repository.entry(7)!!.online)
    }

    @Test
    fun reconcilingKeepsProgressAndOfflineChapters() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 7, title = "本地收藏"))
        repository.saveProgress(ReadingProgress(bookId = 7, chapterId = 42, chapterIndex = 3))
        repository.setCachedChapters(7, setOf(42, 43))

        repository.replaceOnlineEntries(listOf(Book(bookId = 7, title = "本地收藏")))

        val entry = repository.entry(7)!!
        assertEquals(42, entry.progress?.chapterId)
        assertEquals(setOf(42, 43), entry.cachedChapterIds)
    }

    @Test
    fun localOnlyFavouritesSurviveASyncAndStayLocal() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 1, title = "只在本机"))

        repository.replaceOnlineEntries(listOf(Book(bookId = 2, title = "站点书架")))

        assertEquals(setOf(1, 2), repository.entries.value.map { it.book.bookId }.toSet())
        assertFalse(repository.entry(1)!!.online)
        assertTrue(repository.entry(2)!!.online)
    }

    @Test
    fun droppingFromTheSiteShelfLeavesALocalFavouriteAlone() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 1, title = "只在本机"))
        repository.replaceOnlineEntries(listOf(Book(bookId = 2, title = "站点书架")))

        repository.replaceOnlineEntries(emptyList())

        assertEquals(listOf(1), repository.entries.value.map { it.book.bookId })
    }

    @Test
    fun mergedShelfKeepsTheOrderTheSourceSent() = runBlocking {
        val repository = repository()
        repository.load()

        repository.replaceOnlineEntries(
            listOf(
                Book(bookId = 10, title = "第一本"),
                Book(bookId = 20, title = "第二本"),
                Book(bookId = 30, title = "第三本"),
            ),
        )

        // The site lists its newest addition first; the merged shelf must not shuffle it.
        assertEquals(listOf(10, 20, 30), repository.mergedShelf())
    }

    @Test
    fun syncingAgainDoesNotReshuffleOrDuplicate() = runBlocking {
        val repository = repository()
        repository.load()
        repository.replaceOnlineEntries(
            listOf(Book(bookId = 10, title = "A"), Book(bookId = 20, title = "B")),
        )
        val firstPass = repository.entries.value.associate { it.book.bookId to it.addedAt }

        repository.replaceOnlineEntries(
            listOf(Book(bookId = 10, title = "A"), Book(bookId = 20, title = "B")),
        )

        assertEquals(listOf(10, 20), repository.mergedShelf())
        assertEquals(firstPass, repository.entries.value.associate { it.book.bookId to it.addedAt })
    }

    @Test
    fun markOnlineIsRememberedSoARemovalCanReachTheSite() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 7, title = "刚收藏"))

        // Nothing is mirrored until the push to the source actually succeeded.
        assertFalse(repository.entry(7)!!.online)
        repository.markOnline(7)
        assertTrue(repository.entry(7)!!.online)

        // Marking must survive a reload, or the next removal would silently skip the site.
        val reloaded = repository()
        reloaded.load()
        assertTrue(reloaded.entry(7)!!.online)
    }

    @Test
    fun markOnlineOnAMissingBookIsANoOp() = runBlocking {
        val repository = repository()
        repository.load()

        repository.markOnline(999)

        assertTrue(repository.entries.value.isEmpty())
    }

    /**
     * Un-favouriting clears the flag instead of deleting the row, so the reading
     * progress that lives on the same row survives it.
     */
    @Test
    fun clearingTheOnlineFlagKeepsTheLocalRowAndItsProgress() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 7, title = "读过的书"))
        repository.saveProgress(ReadingProgress(bookId = 7, chapterId = 42, chapterIndex = 3))
        repository.markOnline(7)

        repository.markOnline(7, online = false)

        val entry = repository.entry(7)!!
        assertFalse(entry.online)
        assertEquals(42, entry.progress?.chapterId)
    }

    /**
     * The site's bookshelf page is a title, an author and a latest chapter — no cover, no
     * 文库分类 — so a sync must *add to* what the app already knows rather than overwrite it.
     * Assigning the incoming row over the local one is what made a book lose its cover on the
     * sync after the reader had learned it.
     */
    @Test
    fun aSyncKeepsTheMetadataTheSiteShelfPageDoesNotCarry() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(
            Book(
                bookId = 7,
                title = "本地读过的书",
                coverUrl = "http://img.wenku8.com/image/3/7/7s.jpg",
                category = "富士见文库",
                status = "连载中",
                updatedAt = "2026-09-09",
            ),
        )

        repository.replaceOnlineEntries(
            listOf(Book(bookId = 7, title = "站点书名", author = "作者甲", latestChapter = "第四卷")),
        )

        val book = repository.entry(7)!!.book
        // The site's own fields win where it has them…
        assertEquals("站点书名", book.title)
        assertEquals("作者甲", book.author)
        assertEquals("第四卷", book.latestChapter)
        // …and everything it does not mention survives.
        assertEquals("http://img.wenku8.com/image/3/7/7s.jpg", book.coverUrl)
        assertEquals("富士见文库", book.category)
        assertEquals("连载中", book.status)
        assertEquals("2026-09-09", book.updatedAt)
    }

    /** The same merge has to survive a reload, or the cover would come back only until restart. */
    @Test
    fun mergedMetadataSurvivesAReload() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 7, title = "本地读过的书", coverUrl = "http://img.wenku8.com/7s.jpg"))
        repository.replaceOnlineEntries(listOf(Book(bookId = 7, title = "站点书名")))

        val reloaded = repository()
        reloaded.load()

        assertEquals("站点书名", reloaded.entry(7)!!.book.title)
        assertEquals("http://img.wenku8.com/7s.jpg", reloaded.entry(7)!!.book.coverUrl)
    }

    /**
     * [ShelfRepository.updateBook] is how a cover read later from the book's own page reaches a
     * shelf row. It must not disturb anything that belongs to the row rather than the book.
     */
    @Test
    fun updatingOneBooksMetadataLeavesTheRestOfItsRowAlone() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 7, title = "只有书名"), online = true)
        repository.saveProgress(ReadingProgress(bookId = 7, chapterId = 42, chapterIndex = 3))
        repository.setCachedChapters(7, setOf(42, 43))
        val addedAt = repository.entry(7)!!.addedAt

        repository.updateBook(
            Book(
                bookId = 7,
                title = "只有书名",
                coverUrl = "http://img.wenku8.com/image/3/7/7s.jpg",
                category = "富士见文库",
            ),
        )

        val entry = repository.entry(7)!!
        assertEquals("http://img.wenku8.com/image/3/7/7s.jpg", entry.book.coverUrl)
        assertEquals("富士见文库", entry.book.category)
        assertEquals(42, entry.progress?.chapterId)
        assertEquals(setOf(42, 43), entry.cachedChapterIds)
        assertTrue(entry.online)
        assertEquals(addedAt, entry.addedAt)
    }

    /** A book that is not on the shelf cannot be completed into one by a metadata read. */
    @Test
    fun updatingAMissingBookIsANoOp() = runBlocking {
        val repository = repository()
        repository.load()

        repository.updateBook(Book(bookId = 999, title = "凭空的", coverUrl = "http://img.wenku8.com/9s.jpg"))

        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun mergeStateSurvivesAReload() = runBlocking {
        val repository = repository()
        repository.load()
        repository.add(Book(bookId = 1, title = "只在本机"))
        repository.replaceOnlineEntries(listOf(Book(bookId = 2, title = "站点书架")))

        val reloaded = repository()
        reloaded.load()

        assertEquals(setOf(1, 2), reloaded.entries.value.map { it.book.bookId }.toSet())
        assertTrue(reloaded.entry(2)!!.online)
        assertFalse(reloaded.entry(1)!!.online)
    }
}
