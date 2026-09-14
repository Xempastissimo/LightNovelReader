package com.xempastissimo.lightnovelreader.data.repo

import android.content.Context
import com.xempastissimo.lightnovelreader.core.json.Json
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.network.HttpFetcher
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One book's copy on disk: how many chapters it holds and what it costs in space.
 *
 * A summary rather than the chapter list, because the only thing that needs the
 * numbers is a shelf row.
 */
data class OfflineBook(
    val bookId: Int,
    val chapterCount: Int,
    val sizeBytes: Long,
)

/**
 * Offline chapter storage.
 *
 * Chapters are saved as one small JSON document per chapter under
 * `filesDir/library/{bookId}/`. The format is owned by this app, which keeps the
 * reader usable offline after a download without pulling in a database.
 */
class ChapterCache(private val rootDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "library"))

    private fun bookDir(bookId: Int): File = File(rootDir, bookId.toString())

    private fun chapterFile(bookId: Int, chapterId: Int): File =
        File(bookDir(bookId), "$chapterId.json")

    fun isCached(bookId: Int, chapterId: Int): Boolean = chapterFile(bookId, chapterId).let {
        it.exists() && it.length() > 0
    }

    fun cachedChapterIds(bookId: Int): Set<Int> {
        val files = bookDir(bookId).listFiles() ?: return emptySet()
        return files.mapNotNull { file ->
            file.nameWithoutExtension.toIntOrNull()?.takeIf { file.extension == "json" && file.length() > 0 }
        }.toSet()
    }

    /**
     * Every book with at least one chapter on disk.
     *
     * The shelf's own `cachedChapterIds` is a *record* of what a download wrote, and it
     * lags behind the directory in both directions: reading a chapter online writes it to
     * the cache without touching the record, and clearing the cache outside the app leaves
     * the record claiming chapters that are gone. The "已缓存" tab is built from this
     * listing instead, because a directory cannot disagree with itself — and a book that
     * is on disk but missing from the record is one the user could not otherwise delete.
     *
     * A directory that is not named after a book id, or that holds no live chapter, is not
     * an offline book. `.json.tmp` files are half-written chapters and do not count.
     */
    fun offlineBooks(): List<OfflineBook> {
        val dirs = rootDir.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            val bookId = dir.name.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val chapters = dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension == "json" && it.length() > 0 }
            if (chapters.isEmpty()) return@mapNotNull null
            OfflineBook(bookId = bookId, chapterCount = chapters.size, sizeBytes = chapters.sumOf { it.length() })
        }
    }

    suspend fun read(bookId: Int, chapterId: Int): ChapterContent? = withContext(Dispatchers.IO) {
        val file = chapterFile(bookId, chapterId)
        if (!file.exists()) return@withContext null
        val document = runCatching { file.readText() }.getOrNull() ?: return@withContext null
        decode(document)
    }

    suspend fun write(content: ChapterContent) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = bookDir(content.bookId)
            dir.mkdirs()
            val target = chapterFile(content.bookId, content.chapterId)
            val tmp = File(dir, "${content.chapterId}.json.tmp")
            tmp.writeText(encode(content))
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeText(encode(content))
                    tmp.delete()
                }
            }
        }
        Unit
    }

    suspend fun deleteBook(bookId: Int) = withContext(Dispatchers.IO) {
        bookDir(bookId).deleteRecursively()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        rootDir.deleteRecursively()
        Unit
    }

    /** Size of the offline copy of one book, for the "已缓存" badge. */
    fun bookSizeBytes(bookId: Int): Long =
        bookDir(bookId).listFiles()?.sumOf { it.length() } ?: 0L

    fun totalSizeBytes(): Long = rootDir.listFiles()?.sumOf { dir ->
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    } ?: 0L

    private fun encode(content: ChapterContent): String {
        val blocks = Json.array()
        for (block in content.blocks) {
            when (block) {
                is ContentBlock.Paragraph -> blocks.add(Json.obj { put("t", block.text) })
                is ContentBlock.Illustration -> blocks.add(Json.obj { put("i", block.url) })
            }
        }
        return Json.obj {
            put("bookId", content.bookId)
            put("chapterId", content.chapterId)
            put("title", content.title)
            put("volumeTitle", content.volumeTitle)
            put("previous", content.previousChapterId ?: -1)
            put("next", content.nextChapterId ?: -1)
            put("blocks", blocks)
        }.toJson()
    }

    private fun decode(document: String): ChapterContent? {
        val root = Json.parseObject(document) ?: return null
        val bookId = root.int("bookId")
        val chapterId = root.int("chapterId")
        if (bookId <= 0 || chapterId <= 0) return null
        val blocks = root.array("blocks").objects().mapNotNull { item ->
            val text = item.stringOrNull("t")
            val image = item.stringOrNull("i")
            when {
                text != null -> ContentBlock.Paragraph(text)
                image != null -> ContentBlock.Illustration(image)
                else -> null
            }
        }
        return ChapterContent(
            bookId = bookId,
            chapterId = chapterId,
            title = root.string("title"),
            volumeTitle = root.string("volumeTitle"),
            blocks = blocks,
            previousChapterId = root.int("previous", -1).takeIf { it > 0 },
            nextChapterId = root.int("next", -1).takeIf { it > 0 },
        )
    }
}

/**
 * Book access used by the screens.
 *
 * Every screen goes through here, so caching, the in-memory detail cache and the
 * "prefer the offline copy" rule live in one place instead of being re-implemented
 * per screen.
 */
class BookRepository(
    private val source: BookSource,
    private val cache: ChapterCache,
    private val packs: PackStore,
    /**
     * Held here so that deleting a book's local copy also deletes its bookmarks.
     *
     * A bookmark is a note about a *downloaded* copy, so it has nothing to point at once the
     * copy is gone — and putting that rule in the one method that removes a local copy is what
     * keeps every present and future delete path from having to remember it.
     */
    private val bookmarks: BookmarkStore,
) {

    private val detailCache = HashMap<Int, BookDetail>(8)

    /**
     * What a list just showed about a book, so the page opened from that list can draw its
     * cover, title, author and 文库 before the source has answered.
     *
     * A hand-off rather than a cache: it is written when the user taps a row and read once, by
     * the detail screen's first frame. [detail] deliberately does not consult it — a summary
     * is never a substitute for the site's own page, and letting one stand in would mean a
     * book opened from a list could never pick up a chapter tree.
     *
     * Bounded because it is written on every tap: the oldest row is dropped instead of the map
     * growing for the life of the process.
     */
    private val summaries = LinkedHashMap<Int, Book>()

    /** Hands a list row to whatever screen the tap opens. */
    fun rememberSummary(book: Book) {
        summaries.remove(book.bookId)
        summaries[book.bookId] = book
        while (summaries.size > MAX_REMEMBERED_SUMMARIES) {
            val oldest = summaries.keys.firstOrNull() ?: break
            summaries.remove(oldest)
        }
    }

    /**
     * The book as a list showed it, or the book of a detail already read.
     *
     * Null means nothing is known yet and the caller has to show its loading state, which is
     * what happens after a process restart: the hand-off does not survive it.
     */
    fun summary(bookId: Int): Book? = summaries[bookId] ?: detailCache[bookId]?.book

    suspend fun recentUpdates() = source.recentUpdates()

    /** Whether the underlying source currently holds a signed-in session. */
    fun isLoggedIn(): Boolean = source.isLoggedIn()

    /** Whether the source publishes whole-book packs, i.e. whether to offer the download. */
    val supportsPackDownload: Boolean get() = source.supportsPackDownload

    suspend fun rank(type: com.xempastissimo.lightnovelreader.domain.model.RankType, page: Int = 1) =
        source.rank(type, page)

    suspend fun catalog(page: Int = 1, fullOnly: Boolean = false) = source.catalog(page, fullOnly)

    suspend fun search(keyword: String, field: com.xempastissimo.lightnovelreader.domain.model.SearchField) =
        source.search(keyword, field)

    /**
     * Cached detail: re-entering a book does not re-fetch two pages.
     *
     * When the source cannot be reached, a book that has been downloaded whole falls back
     * to the catalogue stored with its pack — that is what lets a downloaded book be
     * opened with no network at all, which a merely cached one cannot. The fallback is
     * deliberately not put into [detailCache]: the next read should try the source again
     * rather than keep serving a snapshot.
     */
    suspend fun detail(bookId: Int, forceRefresh: Boolean = false): BookDetail {
        val cached = detailCache[bookId]
        if (!forceRefresh && cached != null && cached.chapters.isNotEmpty()) return cached
        val detail = try {
            source.detail(bookId)
        } catch (error: Throwable) {
            packs.book(bookId)?.tocDetail()?.takeIf { it.chapters.isNotEmpty() }?.let { return it }
            throw error
        }
        detailCache[bookId] = detail
        return detail
    }

    fun cachedDetail(bookId: Int): BookDetail? = detailCache[bookId]

    /**
     * A book's metadata on its own, without the chapter tree.
     *
     * Used to complete shelf rows the source's bookshelf page described only as a title and
     * an author; deliberately not cached in [detailCache], which holds full details.
     */
    suspend fun bookSummary(bookId: Int): Book = source.bookSummary(bookId)

    suspend fun content(
        bookId: Int,
        chapterId: Int,
        fallbackTitle: String = "",
        forceRefresh: Boolean = false,
    ): ChapterContent {
        if (!forceRefresh) {
            cache.read(bookId, chapterId)?.let { return it }
            packChapter(bookId, chapterId, fallbackTitle)?.let { return it }
        }
        val content = source.content(bookId, chapterId, fallbackTitle)
        if (content.requiresLogin) {
            throw HttpFailure.AuthRequired("该章节需要登录后才能阅读")
        }
        if (content.blocks.isNotEmpty()) cache.write(content)
        return content
    }

    /**
     * One chapter out of the downloaded pack, or null when there is no pack entry for it.
     *
     * The pack is consulted *after* the chapter cache and *before* the network: a chapter
     * that was cached online may carry illustrations the pack never has, while a chapter
     * that only exists in the pack must not cost a request. This is also what keeps a
     * downloaded book readable after 设置 → 存储 → 清理离线章节.
     */
    private suspend fun packChapter(bookId: Int, chapterId: Int, fallbackTitle: String): ChapterContent? {
        val record = packs.book(bookId) ?: return null
        val chapters = record.volumes.flatMap { it.chapters }
        val position = chapters.indexOfFirst { it.chapterId == chapterId }
        if (position < 0) return null
        val paragraphs = packs.readChapter(bookId, chapterId) ?: return null
        if (paragraphs.isEmpty()) return null
        val chapter = chapters[position]
        return ChapterContent(
            bookId = bookId,
            chapterId = chapterId,
            title = chapter.title.ifBlank { fallbackTitle },
            volumeTitle = chapter.volumeTitle,
            blocks = paragraphs.map { ContentBlock.Paragraph(it) },
            previousChapterId = chapters.getOrNull(position - 1)?.chapterId,
            nextChapterId = chapters.getOrNull(position + 1)?.chapterId,
        )
    }

    /**
     * True when the chapter is available offline.
     *
     * Counts the downloaded pack as well as the chapter cache: a book downloaded whole is
     * readable offline even after its cached chapters have been cleared.
     */
    fun isCached(bookId: Int, chapterId: Int): Boolean =
        cache.isCached(bookId, chapterId) || packs.chapterIds(bookId).contains(chapterId)

    fun cachedChapterIds(bookId: Int): Set<Int> =
        cache.cachedChapterIds(bookId) + packs.chapterIds(bookId)

    /**
     * What is actually on disk, one entry per book.
     *
     * Suspending because it walks the library directory: callers are view models, which
     * would otherwise read the file system on the main thread.
     */
    suspend fun offlineBooks(): List<OfflineBook> = withContext(Dispatchers.IO) { cache.offlineBooks() }

    /**
     * Downloads a book chapter by chapter, one at a time.
     *
     * Sequential on purpose: the source throttles aggressive clients, so the
     * pace is set by [com.xempastissimo.lightnovelreader.data.network.RateLimiter].
     * [onProgress] receives (done, total) so the UI can show a determinate bar.
     */
    suspend fun downloadBook(
        detail: BookDetail,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true },
    ): Int {
        val chapters = detail.chapters
        var done = 0
        for (chapter in chapters) {
            if (!shouldContinue()) break
            if (!cache.isCached(detail.book.bookId, chapter.chapterId)) {
                runCatching { content(detail.book.bookId, chapter.chapterId, chapter.title) }
            }
            done++
            onProgress(done, chapters.size)
        }
        return done
    }

    suspend fun deleteOfflineCopy(bookId: Int) = cache.deleteBook(bookId)

    // ------------------------------------------------------------------ pack download

    /** Downloaded books, most recent first. */
    fun downloadedBooks(): List<DownloadedBook> = packs.books.value

    fun downloadedBook(bookId: Int): DownloadedBook? = packs.book(bookId)

    fun totalPackSizeBytes(): Long = packs.totalSizeBytes()

    /** Re-reads the pack directory; cheap enough to run whenever a screen comes back. */
    suspend fun refreshPacks() = packs.load()

    /**
     * Downloads a book's whole text as the source publishes it, then imports it.
     *
     * One request for the whole book — that is the point of the feature: the site's own
     * pack replaces a page load per chapter. The pack file is kept, and its chapters are
     * also written into the chapter cache so that reading does not depend on the pack
     * machinery at all.
     */
    suspend fun downloadPack(
        bookId: Int,
        detail: BookDetail,
        onPhase: (String) -> Unit = {},
    ): DownloadedBook {
        onPhase("正在下载整本…")
        val packed = source.downloadPack(bookId, detail)
            ?: throw HttpFailure.Status(501, "${source.displayName} 未提供整本下载")
        val record = packs.save(packed, detail)
        importPack(record, onPhase)
        return record
    }

    /**
     * Writes the pack's chapters into the offline cache.
     *
     * A chapter already cached *with illustrations* is left alone: the pack has no images
     * (the source's own txt has none either), so overwriting one with the pack's empty
     * illustration chapter would lose the artwork for no gain.
     */
    private suspend fun importPack(record: DownloadedBook, onPhase: (String) -> Unit) {
        val chapters = record.volumes.flatMap { it.chapters }
        val pending = chapters.filter { record.slices.containsKey(it.chapterId) }
        var done = 0
        for ((position, chapter) in chapters.withIndex()) {
            if (!record.slices.containsKey(chapter.chapterId)) continue
            done++
            onPhase("正在导入 $done/${pending.size} 章…")
            val existing = cache.read(record.bookId, chapter.chapterId)
            if (existing != null && existing.illustrations.isNotEmpty()) continue
            val paragraphs = packs.readChapter(record.bookId, chapter.chapterId).orEmpty()
            if (paragraphs.isEmpty()) continue
            cache.write(
                ChapterContent(
                    bookId = record.bookId,
                    chapterId = chapter.chapterId,
                    title = chapter.title,
                    volumeTitle = chapter.volumeTitle,
                    blocks = paragraphs.map { ContentBlock.Paragraph(it) },
                    previousChapterId = chapters.getOrNull(position - 1)?.chapterId,
                    nextChapterId = chapters.getOrNull(position + 1)?.chapterId,
                ),
            )
        }
    }

    /** Deletes the pack file and its index; the imported chapters are [deleteOfflineCopy]'s job. */
    suspend fun deletePack(bookId: Int) = packs.delete(bookId)

    /**
     * Deletes every downloaded pack on the device, and every bookmark with them.
     *
     * The pair is deliberate: 设置 → 存储 → 清理整本下载 takes away the downloaded books, and a
     * bookmark that outlived the book it marks would be a row pointing at nothing.
     */
    suspend fun deleteAllPacks() {
        packs.clear()
        bookmarks.clear()
    }

    /**
     * Deletes every local copy of a book: the imported chapters, the chapter cache, the
     * downloaded pack, and the bookmarks — bookmarks only ever exist for a downloaded book, so
     * they go with the download rather than with the chapter cache.
     */
    suspend fun deleteLocalCopy(bookId: Int) {
        cache.deleteBook(bookId)
        packs.delete(bookId)
        bookmarks.removeAllForBook(bookId)
    }

    fun offlineSizeBytes(bookId: Int): Long = cache.bookSizeBytes(bookId)

    fun totalOfflineSizeBytes(): Long = cache.totalSizeBytes()

    fun invalidate(bookId: Int) {
        detailCache.remove(bookId)
    }

    private companion object {
        /** How many rows' worth of list metadata to keep for the next screen's first frame. */
        const val MAX_REMEMBERED_SUMMARIES = 32
    }
}

/** Small helper so callers can format cache sizes without pulling in a formatter. */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
