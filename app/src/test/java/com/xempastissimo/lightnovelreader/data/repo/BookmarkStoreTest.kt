package com.xempastissimo.lightnovelreader.data.repo

import com.xempastissimo.lightnovelreader.domain.model.Bookmark
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Local bookmarks: a small JSON document next to `shelf.json`.
 *
 * What matters here is what a restart and a deletion depend on — that the file alone is enough
 * to rebuild the list, that a bookmark is identified by its *position* rather than by its
 * arrival order, and that a document the app cannot read degrades to an empty store instead of
 * taking the reader down with it.
 */
class BookmarkStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "library/bookmarks.json")

    private fun store() = BookmarkStore(file) { CREATED_AT }

    private fun bookmark(
        bookId: Int = 1973,
        chapterId: Int = 101,
        paragraphIndex: Int = 3,
        title: String = "第一章 开端",
    ) = Bookmark(
        bookId = bookId,
        chapterId = chapterId,
        chapterIndex = 0,
        chapterTitle = title,
        paragraphIndex = paragraphIndex,
        excerpt = "第一段的开头。",
    )

    @Test
    fun `bookmarks survive being written and read back`() = runTest {
        store().add(bookmark())

        val reloaded = store()
        reloaded.load()

        assertEquals(1, reloaded.bookmarks.value.size)
        val restored = reloaded.bookmarks.value.single()
        assertEquals(1973, restored.bookId)
        assertEquals(101, restored.chapterId)
        assertEquals("第一章 开端", restored.chapterTitle)
        assertEquals(3, restored.paragraphIndex)
        assertEquals("第一段的开头。", restored.excerpt)
        assertEquals(CREATED_AT, restored.createdAt)
    }

    @Test
    fun `a bookmark without a timestamp is stamped by the store`() = runTest {
        val store = store()

        store.add(bookmark())

        assertEquals(CREATED_AT, store.bookmarks.value.single().createdAt)
    }

    /** The list is read newest first, and one book's list never shows another's. */
    @Test
    fun `forBook filters by book and lists newest first`() = runTest {
        val store = store()
        store.add(bookmark(bookId = 1973, chapterId = 101, paragraphIndex = 0))
        store.add(bookmark(bookId = 42, chapterId = 900, paragraphIndex = 7))
        store.add(bookmark(bookId = 1973, chapterId = 102, paragraphIndex = 1))

        val mine = store.forBook(1973)

        assertEquals(2, mine.size)
        assertEquals(102, mine[0].chapterId)
        assertEquals(101, mine[1].chapterId)
        assertEquals(1, store.forBook(42).size)
        assertEquals(2, store.countForBook(1973))
        assertEquals(0, store.countForBook(7))
    }

    @Test
    fun `a position is either bookmarked or not`() = runTest {
        val store = store()
        store.add(bookmark(chapterId = 101, paragraphIndex = 3))

        assertTrue(store.isBookmarked(1973, 101, 3))
        assertFalse(store.isBookmarked(1973, 101, 4))
        assertFalse(store.isBookmarked(1973, 102, 3))
        assertFalse(store.isBookmarked(42, 101, 3))
    }

    /**
     * Adding twice at the same position replaces rather than appends: the reader's toggle is a
     * single control, so two identical rows would need two taps to undo.
     */
    @Test
    fun `adding at the same position does not duplicate`() = runTest {
        val store = store()

        store.add(bookmark(paragraphIndex = 3, title = "旧标题"))
        store.add(bookmark(paragraphIndex = 3, title = "新标题"))

        assertEquals(1, store.bookmarks.value.size)
        assertEquals("新标题", store.bookmarks.value.single().chapterTitle)
    }

    @Test
    fun `remove drops exactly the one position`() = runTest {
        val store = store()
        store.add(bookmark(chapterId = 101, paragraphIndex = 3))
        store.add(bookmark(chapterId = 101, paragraphIndex = 9))

        store.remove(1973, 101, 3)

        assertEquals(listOf(9), store.bookmarks.value.map { it.paragraphIndex })
    }

    /** Deleting one book's download must leave every other book's bookmarks alone. */
    @Test
    fun `removeAllForBook only touches that book`() = runTest {
        val store = store()
        store.add(bookmark(bookId = 1973, chapterId = 101, paragraphIndex = 1))
        store.add(bookmark(bookId = 1973, chapterId = 102, paragraphIndex = 2))
        store.add(bookmark(bookId = 42, chapterId = 900, paragraphIndex = 3))

        store.removeAllForBook(1973)

        assertEquals(listOf(42), store.bookmarks.value.map { it.bookId })
    }

    @Test
    fun `clear empties the store and the file`() = runTest {
        val store = store()
        store.add(bookmark())
        store.add(bookmark(bookId = 42, chapterId = 900))

        store.clear()

        assertTrue(store.bookmarks.value.isEmpty())
        val reloaded = store()
        reloaded.load()
        assertTrue(reloaded.bookmarks.value.isEmpty())
    }

    /**
     * The file is the only copy, so a document that cannot be parsed is an empty store rather
     * than an exception — the same rule `ShelfRepository` follows.
     */
    @Test
    fun `a corrupted document reads as empty`() = runTest {
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")

        val store = store()
        store.load()

        assertTrue(store.bookmarks.value.isEmpty())
    }

    /** A row that cannot name a book and a chapter is not a bookmark and is skipped. */
    @Test
    fun `rows without a book and chapter are ignored`() = runTest {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"bookmarks":[
              {"bookId":0,"chapterId":101,"paragraphIndex":1},
              {"bookId":1973,"chapterId":0,"paragraphIndex":1},
              {"bookId":1973,"chapterId":101,"paragraphIndex":5,"excerpt":"保留"}
            ]}
            """.trimIndent(),
        )

        val store = store()
        store.load()

        assertEquals(1, store.bookmarks.value.size)
        assertEquals(5, store.bookmarks.value.single().paragraphIndex)
    }

    @Test
    fun `an absent file starts empty`() = runTest {
        val store = store()

        store.load()

        assertTrue(store.bookmarks.value.isEmpty())
    }

    private companion object {
        const val CREATED_AT = 1_789_225_000_000L
    }
}
