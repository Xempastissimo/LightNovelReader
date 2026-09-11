package com.xempastissimo.lightnovelreader.data.repo

import android.content.Context
import com.xempastissimo.lightnovelreader.core.json.Json
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.network.HttpFetcher
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
) {

    private val detailCache = HashMap<Int, BookDetail>(8)

    suspend fun recentUpdates() = source.recentUpdates()

    /** Whether the underlying source currently holds a signed-in session. */
    fun isLoggedIn(): Boolean = source.isLoggedIn()

    suspend fun rank(type: com.xempastissimo.lightnovelreader.domain.model.RankType, page: Int = 1) =
        source.rank(type, page)

    suspend fun catalog(page: Int = 1, fullOnly: Boolean = false) = source.catalog(page, fullOnly)

    suspend fun search(keyword: String, field: com.xempastissimo.lightnovelreader.domain.model.SearchField) =
        source.search(keyword, field)

    /** Cached detail: re-entering a book does not re-fetch two pages. */
    suspend fun detail(bookId: Int, forceRefresh: Boolean = false): BookDetail {
        val cached = detailCache[bookId]
        if (!forceRefresh && cached != null && cached.chapters.isNotEmpty()) return cached
        val detail = source.detail(bookId)
        detailCache[bookId] = detail
        return detail
    }

    fun cachedDetail(bookId: Int): BookDetail? = detailCache[bookId]

    suspend fun content(
        bookId: Int,
        chapterId: Int,
        fallbackTitle: String = "",
        forceRefresh: Boolean = false,
    ): ChapterContent {
        if (!forceRefresh) {
            cache.read(bookId, chapterId)?.let { return it }
        }
        val content = source.content(bookId, chapterId, fallbackTitle)
        if (content.requiresLogin) {
            throw HttpFailure.AuthRequired("该章节需要登录后才能阅读")
        }
        if (content.blocks.isNotEmpty()) cache.write(content)
        return content
    }

    /** True when the chapter is available offline. */
    fun isCached(bookId: Int, chapterId: Int): Boolean = cache.isCached(bookId, chapterId)

    fun cachedChapterIds(bookId: Int): Set<Int> = cache.cachedChapterIds(bookId)

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

    fun offlineSizeBytes(bookId: Int): Long = cache.bookSizeBytes(bookId)

    fun totalOfflineSizeBytes(): Long = cache.totalSizeBytes()

    fun invalidate(bookId: Int) {
        detailCache.remove(bookId)
    }
}

/** Small helper so callers can format cache sizes without pulling in a formatter. */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
