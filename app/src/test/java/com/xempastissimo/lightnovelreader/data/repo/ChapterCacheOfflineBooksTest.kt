package com.xempastissimo.lightnovelreader.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ChapterCache.offlineBooks] is what the 已缓存 tab is built from, so what it counts is what
 * the user is told they have — and what they are allowed to delete.
 *
 * The cases below are the ones where a naive directory listing would lie: a half-written
 * chapter counted as a complete one, a stray directory read as a book, or an empty file
 * making a book look readable offline when opening it would silently need the network.
 */
class ChapterCacheOfflineBooksTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val library: File get() = File(folder.root, "library")

    private fun cache() = ChapterCache(library)

    private fun chapter(bookId: Int, chapterId: Int, content: String = "正文") {
        val dir = File(library, bookId.toString())
        dir.mkdirs()
        File(dir, "$chapterId.json").writeText(content)
    }

    @Test
    fun `books are listed with their chapter count and the space they take`() {
        chapter(1973, 1, "a".repeat(100))
        chapter(1973, 2, "b".repeat(50))
        chapter(3988, 7, "c".repeat(30))

        val books = cache().offlineBooks().associateBy { it.bookId }

        assertEquals(setOf(1973, 3988), books.keys)
        assertEquals(2, books.getValue(1973).chapterCount)
        assertEquals(150L, books.getValue(1973).sizeBytes)
        assertEquals(1, books.getValue(3988).chapterCount)
        assertEquals(30L, books.getValue(3988).sizeBytes)
    }

    /**
     * A chapter is written to `<id>.json.tmp` and then renamed, so a `.tmp` file is a write
     * the process did not finish. Counting one would show a chapter the reader cannot
     * actually open offline.
     */
    @Test
    fun `a half written chapter is not a cached chapter`() {
        chapter(1973, 1)
        val dir = File(library, "1973")
        File(dir, "2.json.tmp").writeText("正文写了一半")

        val book = cache().offlineBooks().single()

        assertEquals(1, book.chapterCount)
        assertEquals(File(dir, "1.json").length(), book.sizeBytes)
    }

    /** Empty files are what a failed write leaves behind; they hold no chapter. */
    @Test
    fun `empty files do not make a book readable offline`() {
        chapter(1973, 1, content = "")

        assertTrue(cache().offlineBooks().isEmpty())
    }

    /** Only a directory named after a book id is a book, and only `.json` files are chapters. */
    @Test
    fun `stray directories and files are ignored`() {
        chapter(1973, 1)
        File(library, "notes").mkdirs()
        File(library, "1973/cover.png").writeText("x")
        File(library, "book.json").writeText("x")

        val books = cache().offlineBooks()

        assertEquals(listOf(1973), books.map { it.bookId })
        assertEquals(1, books.single().chapterCount)
    }

    /** Nothing cached yet is the ordinary state, not an error. */
    @Test
    fun `a library that does not exist yet is empty`() {
        assertTrue(cache().offlineBooks().isEmpty())
    }

    /**
     * The listing is meant to survive what the shelf's own record does not: a chapter cached
     * because it was read online — which never records a chapter id anywhere — still has to
     * appear, or the copy would be invisible and therefore undeletable.
     */
    @Test
    fun `a book cached without any record still shows up`() {
        chapter(4242, 11)

        assertEquals(listOf(4242), cache().offlineBooks().map { it.bookId })
    }
}
