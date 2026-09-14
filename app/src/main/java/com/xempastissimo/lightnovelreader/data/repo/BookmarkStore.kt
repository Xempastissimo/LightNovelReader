package com.xempastissimo.lightnovelreader.data.repo

import android.content.Context
import com.xempastissimo.lightnovelreader.core.json.Json
import com.xempastissimo.lightnovelreader.domain.model.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's local bookmarks: one small JSON document, written atomically.
 *
 * Deliberately shaped like [ShelfRepository] — a single document, a mutex, a `StateFlow`, and
 * a format owned by this app — because the data set is the same order of magnitude (hundreds
 * of rows at most) and `core/json` keeps the whole thing testable on the JVM, which a database
 * dependency would not.
 *
 * The file lives at `filesDir/library/bookmarks.json`, next to `shelf.json`. It must **not** go
 * inside `library/{bookId}/`: `ChapterCache.offlineBooks` treats every non-empty `.json` in a
 * book's directory as a cached chapter, so a bookmark file there would inflate both the
 * chapter count and the reported size of the 已缓存 tab.
 *
 * Bookmarks exist only for books downloaded whole. They are deleted with the download by
 * `BookRepository.deleteLocalCopy`, which is the single place that removes a book's local copy.
 */
class BookmarkStore(
    private val storageFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(File(context.filesDir, "library/bookmarks.json"))

    private val mutex = Mutex()
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    /** Every bookmark in the app, newest first. */
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val document = runCatching { if (storageFile.exists()) storageFile.readText() else "" }
                .getOrDefault("")
            _bookmarks.value = parse(document)
        }
    }

    /** One book's bookmarks, newest first. */
    fun forBook(bookId: Int): List<Bookmark> = _bookmarks.value.filter { it.bookId == bookId }

    fun countForBook(bookId: Int): Int = _bookmarks.value.count { it.bookId == bookId }

    fun isBookmarked(bookId: Int, chapterId: Int, paragraphIndex: Int): Boolean =
        _bookmarks.value.any { it.matches(bookId, chapterId, paragraphIndex) }

    /**
     * Adds a bookmark, or replaces the one already at that exact position.
     *
     * "One bookmark per position" is what makes the reader's single toggle honest: tapping it
     * twice must not leave two identical rows that each need deleting.
     */
    suspend fun add(bookmark: Bookmark) = mutate { current ->
        val stamped = if (bookmark.createdAt > 0L) bookmark else bookmark.copy(createdAt = clock())
        listOf(stamped) + current.filterNot {
            it.matches(bookmark.bookId, bookmark.chapterId, bookmark.paragraphIndex)
        }
    }

    suspend fun remove(bookId: Int, chapterId: Int, paragraphIndex: Int) = mutate { current ->
        current.filterNot { it.matches(bookId, chapterId, paragraphIndex) }
    }

    /**
     * Drops everything one book held.
     *
     * Called when a book's download is deleted, which is the only thing that takes its
     * bookmarks with it: a bookmark is a note about a downloaded copy, so it has nothing to
     * point at once the copy is gone.
     */
    suspend fun removeAllForBook(bookId: Int) = mutate { current ->
        current.filterNot { it.bookId == bookId }
    }

    /** Wipes every bookmark; used by 设置 → 存储 → 清理整本下载. */
    suspend fun clear() = mutate { emptyList() }

    private suspend fun mutate(transform: (List<Bookmark>) -> List<Bookmark>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _bookmarks.value = transform(_bookmarks.value)
                persist()
            }
        }

    private fun persist() {
        val array = Json.array()
        for (bookmark in _bookmarks.value) {
            array.add(
                Json.obj {
                    put("bookId", bookmark.bookId)
                    put("chapterId", bookmark.chapterId)
                    put("chapterIndex", bookmark.chapterIndex)
                    put("chapterTitle", bookmark.chapterTitle)
                    put("paragraphIndex", bookmark.paragraphIndex)
                    put("excerpt", bookmark.excerpt)
                    put("createdAt", bookmark.createdAt)
                },
            )
        }
        val root = Json.obj {
            put("version", 1)
            put("bookmarks", array)
        }
        writeAtomically(root.toJson())
    }

    /**
     * A document that cannot be read is an empty store, not a crash and not a partial list:
     * the same rule `ShelfRepository.parse` follows, and the reason a half-written file from a
     * killed process does not take the reader down with it.
     */
    private fun parse(document: String): List<Bookmark> {
        val root = Json.parseObject(document) ?: return emptyList()
        return root.array("bookmarks").objects().mapNotNull { item ->
            val bookId = item.int("bookId")
            val chapterId = item.int("chapterId")
            if (bookId <= 0 || chapterId <= 0) return@mapNotNull null
            Bookmark(
                bookId = bookId,
                chapterId = chapterId,
                chapterIndex = item.int("chapterIndex"),
                chapterTitle = item.string("chapterTitle"),
                paragraphIndex = item.int("paragraphIndex"),
                excerpt = item.string("excerpt"),
                createdAt = item.long("createdAt"),
            )
        }
    }

    private fun writeAtomically(payload: String) {
        runCatching {
            val parent = storageFile.parentFile
            if (parent == null) {
                storageFile.writeText(payload)
                return
            }
            parent.mkdirs()
            val tmp = File(parent, storageFile.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(storageFile)) {
                storageFile.delete()
                if (!tmp.renameTo(storageFile)) {
                    storageFile.writeText(payload)
                    tmp.delete()
                }
            }
        }
    }
}

/** Bookmarks are per position, so this is the identity that `add` replaces and `remove` drops. */
private fun Bookmark.matches(bookId: Int, chapterId: Int, paragraphIndex: Int): Boolean =
    this.bookId == bookId && this.chapterId == chapterId && this.paragraphIndex == paragraphIndex
