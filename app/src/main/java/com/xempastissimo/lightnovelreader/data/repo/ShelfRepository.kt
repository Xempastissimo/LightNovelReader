package com.xempastissimo.lightnovelreader.data.repo

import android.content.Context
import com.xempastissimo.lightnovelreader.core.json.Json
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.ReadingProgress
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's local library: which books are on the shelf, where they stopped
 * reading, and which chapters are available offline.
 *
 * Storage is a single JSON document written atomically. The data set is small
 * (hundreds of books at most), reads happen once at start-up, and writes are
 * debounced by the caller, so a database would add a dependency without adding
 * capability — and Room would drag in KSP, which the offline toolchain cannot
 * currently resolve.
 */
class ShelfRepository(
    private val storageFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(File(context.filesDir, "library/shelf.json"))

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<ShelfEntry>>(emptyList())
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())

    val entries: StateFlow<List<ShelfEntry>> = _entries.asStateFlow()

    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /** Books ordered by most recently read, which is what the shelf screen shows. */
    val recent: StateFlow<List<ShelfEntry>>
        get() = _recent

    private val _recent = MutableStateFlow<List<ShelfEntry>>(emptyList())

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val document = runCatching { if (storageFile.exists()) storageFile.readText() else "" }
                .getOrDefault("")
            val parsed = parse(document)
            _entries.value = parsed.first
            _searchHistory.value = parsed.second
            publishRecent()
        }
    }

    fun isOnShelf(bookId: Int): Boolean = _entries.value.any { it.book.bookId == bookId }

    fun entry(bookId: Int): ShelfEntry? = _entries.value.firstOrNull { it.book.bookId == bookId }

    fun progress(bookId: Int): ReadingProgress? = entry(bookId)?.progress

    suspend fun add(book: Book, online: Boolean = false) = mutate { current ->
        if (current.any { it.book.bookId == book.bookId }) {
            // Refresh the metadata, keep progress and cache state.
            current.map { item ->
                if (item.book.bookId == book.bookId) item.copy(book = book.mergeInto(item.book)) else item
            }
        } else {
            current + ShelfEntry(book = book, addedAt = clock(), online = online)
        }
    }

    suspend fun remove(bookId: Int) = mutate { current -> current.filterNot { it.book.bookId == bookId } }

    suspend fun saveProgress(progress: ReadingProgress) = mutate { current ->
        val index = current.indexOfFirst { it.book.bookId == progress.bookId }
        if (index < 0) {
            current
        } else {
            current.toMutableList().also { list ->
                list[index] = list[index].copy(progress = progress)
            }
        }
    }

    suspend fun setCachedChapters(bookId: Int, chapterIds: Set<Int>) = mutate { current ->
        val index = current.indexOfFirst { it.book.bookId == bookId }
        if (index < 0) {
            current
        } else {
            current.toMutableList().also { list ->
                list[index] = list[index].copy(cachedChapterIds = chapterIds)
            }
        }
    }

    /**
     * Replaces the mirror of the source's own bookshelf.
     *
     * A book on the source's shelf *subsumes* any local copy of it: the two are the
     * same favourite, and the shelf screen now shows them in one list, so keeping
     * both would double every row the user has favourited from inside the app.
     *
     * The incoming rows are *sparse* — the site's bookshelf page carries a title, an author
     * and the latest chapter and nothing else, so every cover, 文库分类, 状态 and 更新日期 it
     * does not mention must be merged in rather than assigned over. Replacing the row used to
     * strip a cover the app had already learned, which is why a book could show artwork on one
     * sync and a placeholder letter on the next.
     */
    suspend fun replaceOnlineEntries(books: List<Book>) {
        val now = clock()
        mutate { current ->
            val known = current.associateBy { it.book.bookId }
            val onlineIds = books.mapTo(HashSet()) { it.bookId }
            val localOnly = current.filterNot { it.online || it.book.bookId in onlineIds }
            val merged = books.mapIndexed { index, book ->
                val existing = known[book.bookId]
                ShelfEntry(
                    book = existing?.let { book.mergeInto(it.book) } ?: book,
                    // An entry seen before keeps its timestamp so the merged shelf does
                    // not reshuffle on every sync; a newly discovered one is stamped
                    // "now", minus its position so the site's own ordering survives.
                    addedAt = existing?.addedAt ?: (now - index),
                    progress = existing?.progress,
                    cachedChapterIds = existing?.cachedChapterIds ?: emptySet(),
                    online = true,
                )
            }
            localOnly + merged
        }
    }

    /**
     * Refreshes one book's metadata, leaving the rest of its row alone.
     *
     * Used when a fuller description of a book arrives after its shelf row was written —
     * a detail page read to fill in a cover, say. Progress, cached chapters and the online
     * flag all belong to the row rather than to the metadata, so they are carried across.
     */
    suspend fun updateBook(book: Book) = mutate { current ->
        current.map { entry ->
            if (entry.book.bookId == book.bookId) {
                entry.copy(book = book.mergeInto(entry.book))
            } else {
                entry
            }
        }
    }

    /**
     * Records that a book is now on the source's own bookshelf too.
     *
     * Called after a successful push so the entry is recognised as mirrored, which is
     * what lets a later removal know it has to reach the site as well.
     */
    suspend fun markOnline(bookId: Int, online: Boolean = true) = mutate { current ->
        val index = current.indexOfFirst { it.book.bookId == bookId }
        if (index < 0) {
            current
        } else {
            current.toMutableList().also { list ->
                list[index] = list[index].copy(online = online)
            }
        }
    }

    suspend fun recordSearch(keyword: String) = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withContext
        mutex.withLock {
            val updated = (listOf(trimmed) + _searchHistory.value.filterNot { it == trimmed }).take(MAX_SEARCH_HISTORY)
            _searchHistory.value = updated
            persist()
        }
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _searchHistory.value = emptyList()
            persist()
        }
    }

    private suspend fun mutate(transform: (List<ShelfEntry>) -> List<ShelfEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value = transform(_entries.value)
            publishRecent()
            persist()
        }
    }

    private fun publishRecent() {
        _recent.value = _entries.value.sortedByDescending { it.lastReadAt }
    }

    private fun persist() {
        val array = Json.array()
        for (entry in _entries.value) {
            array.add(
                Json.obj {
                    put("bookId", entry.book.bookId)
                    put("title", entry.book.title)
                    put("shortTitle", entry.book.shortTitle)
                    put("author", entry.book.author)
                    put("coverUrl", entry.book.coverUrl)
                    put("category", entry.book.category)
                    put("status", entry.book.status)
                    put("latestChapter", entry.book.latestChapter)
                    put("updatedAt", entry.book.updatedAt)
                    put("addedAt", entry.addedAt)
                    put("online", entry.online)
                    val progress = entry.progress
                    if (progress != null) {
                        val progressJson = Json.obj {
                            put("bookId", progress.bookId)
                            put("chapterId", progress.chapterId)
                            put("chapterIndex", progress.chapterIndex)
                            put("paragraphIndex", progress.paragraphIndex)
                            put("percentInChapter", progress.percentInChapter.toDouble())
                            put("updatedAt", progress.updatedAt)
                        }
                        put("progress", progressJson)
                    }
                    put("cached", entry.cachedChapterIds.map { it.toString() })
                },
            )
        }
        val root = Json.obj {
            put("version", 1)
            put("entries", array)
            put("searchHistory", _searchHistory.value)
        }
        writeAtomically(root.toJson())
    }

    private fun parse(document: String): Pair<List<ShelfEntry>, List<String>> {
        val root = Json.parseObject(document) ?: return emptyList<ShelfEntry>() to emptyList()
        val entries = root.array("entries").objects().mapNotNull { item ->
            val bookId = item.int("bookId")
            if (bookId <= 0) return@mapNotNull null
            val book = Book(
                bookId = bookId,
                title = item.string("title"),
                shortTitle = item.string("shortTitle", item.string("title")),
                author = item.string("author"),
                coverUrl = item.stringOrNull("coverUrl"),
                category = item.string("category"),
                status = item.string("status"),
                latestChapter = item.string("latestChapter"),
                updatedAt = item.string("updatedAt"),
            )
            val progress = item.entries["progress"]?.let { raw ->
                val obj = raw as? Json.Obj ?: return@let null
                ReadingProgress(
                    bookId = obj.int("bookId", bookId),
                    chapterId = obj.int("chapterId"),
                    chapterIndex = obj.int("chapterIndex"),
                    paragraphIndex = obj.int("paragraphIndex"),
                    percentInChapter = obj.double("percentInChapter").toFloat(),
                    updatedAt = obj.long("updatedAt"),
                )
            }
            ShelfEntry(
                book = book,
                addedAt = item.long("addedAt"),
                progress = progress,
                cachedChapterIds = item.strings("cached").mapNotNull { it.toIntOrNull() }.toSet(),
                online = item.boolean("online"),
            )
        }
        return entries to root.strings("searchHistory")
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

    private companion object {
        const val MAX_SEARCH_HISTORY = 20
    }
}

/** Keeps the richer of two copies of the same book's metadata. */
private fun Book.mergeInto(existing: Book): Book = copy(
    title = title.ifBlank { existing.title },
    author = author.ifBlank { existing.author },
    coverUrl = coverUrl ?: existing.coverUrl,
    category = category.ifBlank { existing.category },
    status = status.ifBlank { existing.status },
    latestChapter = latestChapter.ifBlank { existing.latestChapter },
    updatedAt = updatedAt.ifBlank { existing.updatedAt },
)
