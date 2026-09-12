package com.xempastissimo.lightnovelreader.data.repo

import android.content.Context
import com.xempastissimo.lightnovelreader.core.json.Json
import com.xempastissimo.lightnovelreader.core.text.CharsetCodec
import com.xempastissimo.lightnovelreader.core.text.TextCleaner
import com.xempastissimo.lightnovelreader.data.source.PackSlice
import com.xempastissimo.lightnovelreader.data.source.PackedBook
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One book whose whole text is on this device as the source's own pack file.
 *
 * [slices] is what makes the pack readable without decoding it again: each chapter's body
 * is a byte range of `text.txt`, still valid after a restart. [volumes] is the catalogue
 * as it was when the pack was matched, which doubles as the offline table of contents —
 * a downloaded book can be opened with the network off, which a merely cached one cannot.
 */
data class DownloadedBook(
    val bookId: Int,
    val book: Book,
    val intro: String,
    /** Charset the pack was published in; slices are decoded with it. */
    val charsetName: String,
    val sourceUrl: String,
    /** Size of `text.txt` on disk. */
    val bytes: Long,
    val downloadedAt: Long,
    /** How many chapters the catalogue had when the pack was matched. */
    val tocTotal: Int,
    /** How many of them the pack holds text for. */
    val covered: Int,
    val slices: Map<Int, PackSlice>,
    val volumes: List<Volume>,
) {
    val chapterIds: Set<Int> get() = slices.keys

    /** The catalogue to serve when the source cannot be reached. */
    fun tocDetail(): BookDetail = BookDetail(
        book = book,
        intro = intro,
        volumes = volumes,
        novelPrefix = "",
    )
}

/**
 * Whole-book packs: the source's own txt, kept verbatim, plus the index that makes it
 * readable.
 *
 * Layout, under `filesDir/packs/`:
 *
 * ```
 * {bookId}/
 * ├── text.txt     the pack exactly as downloaded (UTF-8 or GBK)
 * └── index.json   book metadata, the catalogue, and each chapter's byte range
 * ```
 *
 * The pack is kept rather than thrown away after its chapters have been imported, because
 * it is the artefact the source offered: re-importing needs no second download, and the
 * bytes on disk still decode to the same text even if the app's chapter files are cleared
 * from settings.
 *
 * A directory without a readable `index.json` is ignored rather than guessed at: there is
 * nothing in it that can be shown in a list or opened, and `clear()` reclaims it.
 */
class PackStore(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(File(context.filesDir, "packs"))

    private val mutex = Mutex()
    private val _books = MutableStateFlow<List<DownloadedBook>>(emptyList())

    /** Downloaded books, most recent first. Read on the main thread; kept in memory. */
    val books: StateFlow<List<DownloadedBook>> = _books.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { _books.value = scan() }
    }

    fun book(bookId: Int): DownloadedBook? = _books.value.firstOrNull { it.bookId == bookId }

    fun chapterIds(bookId: Int): Set<Int> = book(bookId)?.chapterIds.orEmpty()

    /**
     * Writes the pack and its index.
     *
     * The text is renamed into place before the index is written, so a crash in between
     * leaves a directory the next scan ignores instead of a record pointing at a
     * half-written file.
     */
    suspend fun save(packed: PackedBook, detail: BookDetail): DownloadedBook = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bookId = detail.book.bookId
            val dir = bookDir(bookId)
            dir.mkdirs()
            writePackText(dir, packed.bytes)

            val record = DownloadedBook(
                bookId = bookId,
                book = detail.book,
                intro = detail.intro,
                charsetName = packed.charsetName,
                sourceUrl = packed.sourceUrl,
                bytes = File(dir, TEXT_FILE).length(),
                downloadedAt = clock(),
                tocTotal = detail.chapters.size,
                covered = packed.coveredChapters,
                slices = packed.slices,
                volumes = detail.volumes,
            )
            writeIndex(dir, record)
            // Re-read rather than insert: the file just written is what a later start of
            // the app will see, so it is also what the list should show now.
            _books.value = scan()
            record
        }
    }

    /**
     * A chapter's paragraphs, read straight out of the pack.
     *
     * Returns null when the chapter is not in the pack, or when the pack no longer holds
     * the recorded range (a truncated file), so the caller can fall back to the network
     * instead of serving half a chapter.
     */
    suspend fun readChapter(bookId: Int, chapterId: Int): List<String>? = withContext(Dispatchers.IO) {
        val record = book(bookId) ?: return@withContext null
        val slice = record.slices[chapterId] ?: return@withContext null
        val file = File(bookDir(bookId), TEXT_FILE)
        if (!file.exists() || slice.length <= 0) return@withContext null
        val charset = CharsetCodec.charsetFor(record.charsetName) ?: Charsets.UTF_8

        val bytes = runCatching {
            RandomAccessFile(file, "r").use { stream ->
                val end = slice.offset.toLong() + slice.length
                if (slice.offset < 0 || end > stream.length()) return@use null
                ByteArray(slice.length).also { buffer ->
                    stream.seek(slice.offset.toLong())
                    stream.readFully(buffer)
                }
            }
        }.getOrNull() ?: return@withContext null

        TextCleaner.paragraphs(decode(bytes, charset))
    }

    suspend fun delete(bookId: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            bookDir(bookId).deleteRecursively()
            _books.value = scan()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            rootDir.deleteRecursively()
            _books.value = emptyList()
        }
    }

    fun totalSizeBytes(): Long = _books.value.sumOf { it.bytes }

    // ------------------------------------------------------------------ internals

    private fun bookDir(bookId: Int): File = File(rootDir, bookId.toString())

    private fun writePackText(dir: File, bytes: ByteArray) {
        val target = File(dir, TEXT_FILE)
        val tmp = File(dir, "$TEXT_FILE.tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
            }
        }
    }

    private fun writeIndex(dir: File, record: DownloadedBook) {
        val volumes = Json.array()
        for (volume in record.volumes) {
            val chapters = Json.array()
            for (chapter in volume.chapters) {
                val slice = record.slices[chapter.chapterId]
                chapters.add(
                    Json.obj {
                        put("id", chapter.chapterId)
                        put("title", chapter.title)
                        put("index", chapter.index)
                        // Absent when the pack does not hold this chapter: the record keeps
                        // the full catalogue, so the reader can still list and fetch it.
                        if (slice != null) {
                            put("offset", slice.offset)
                            put("length", slice.length)
                        }
                    },
                )
            }
            volumes.add(
                Json.obj {
                    put("id", volume.volumeId)
                    put("title", volume.title)
                    put("chapters", chapters)
                },
            )
        }

        val root = Json.obj {
            put("version", FORMAT_VERSION)
            put("bookId", record.bookId)
            put("title", record.book.title)
            put("shortTitle", record.book.shortTitle)
            put("author", record.book.author)
            put("coverUrl", record.book.coverUrl)
            put("category", record.book.category)
            put("status", record.book.status)
            put("latestChapter", record.book.latestChapter)
            put("updatedAt", record.book.updatedAt)
            put("intro", record.intro)
            put("charset", record.charsetName)
            put("url", record.sourceUrl)
            put("bytes", record.bytes)
            put("downloadedAt", record.downloadedAt)
            put("tocTotal", record.tocTotal)
            put("covered", record.covered)
            put("volumes", volumes)
        }
        writeAtomically(File(dir, INDEX_FILE), root.toJson())
    }

    private fun parseIndex(file: File, bookId: Int): DownloadedBook? {
        val document = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        val root = Json.parseObject(document) ?: return null
        if (root.int("bookId") != bookId) return null

        val volumes = ArrayList<Volume>(root.array("volumes").size())
        val slices = LinkedHashMap<Int, PackSlice>()
        var volumeSeq = 0
        for (volumeObject in root.array("volumes").objects()) {
            val chapters = ArrayList<Chapter>()
            for (chapterObject in volumeObject.array("chapters").objects()) {
                val chapterId = chapterObject.int("id")
                if (chapterId <= 0) continue
                chapters.add(
                    Chapter(
                        chapterId = chapterId,
                        title = chapterObject.string("title"),
                        volumeId = volumeObject.int("id", volumeSeq),
                        volumeTitle = volumeObject.string("title"),
                        index = chapterObject.int("index"),
                    ),
                )
                if (chapterObject.has("length")) {
                    val length = chapterObject.int("length")
                    if (length > 0) {
                        slices[chapterId] = PackSlice(chapterObject.int("offset"), length)
                    }
                }
            }
            volumes.add(
                Volume(
                    volumeId = volumeObject.int("id", volumeSeq),
                    title = volumeObject.string("title"),
                    chapters = chapters,
                ),
            )
            volumeSeq++
        }
        if (volumes.isEmpty() || slices.isEmpty()) return null

        return DownloadedBook(
            bookId = bookId,
            book = Book(
                bookId = bookId,
                title = root.string("title"),
                shortTitle = root.string("shortTitle", root.string("title")),
                author = root.string("author"),
                coverUrl = root.stringOrNull("coverUrl"),
                category = root.string("category"),
                status = root.string("status"),
                latestChapter = root.string("latestChapter"),
                updatedAt = root.string("updatedAt"),
            ),
            intro = root.string("intro"),
            charsetName = root.string("charset", "UTF-8"),
            sourceUrl = root.string("url"),
            bytes = root.long("bytes"),
            downloadedAt = root.long("downloadedAt"),
            tocTotal = root.int("tocTotal"),
            covered = root.int("covered", slices.size),
            slices = slices,
            volumes = volumes,
        )
    }

    private fun scan(): List<DownloadedBook> {
        val dirs = rootDir.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            val bookId = dir.name.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            if (!File(dir, TEXT_FILE).exists()) return@mapNotNull null
            parseIndex(File(dir, INDEX_FILE), bookId)
        }.sortedByDescending { it.downloadedAt }
    }

    /** GBK is not valid UTF-8, so a strict decode of the wrong charset is caught first. */
    private fun decode(bytes: ByteArray, charset: Charset): String = runCatching {
        charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { String(bytes, charset) }

    private fun writeAtomically(file: File, payload: String) {
        runCatching {
            val parent = file.parentFile
            if (parent == null) {
                file.writeText(payload)
                return
            }
            parent.mkdirs()
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(payload)
                    tmp.delete()
                }
            }
        }
    }

    private companion object {
        const val TEXT_FILE = "text.txt"
        const val INDEX_FILE = "index.json"
        const val FORMAT_VERSION = 1
    }
}

/** When a pack was downloaded, as `yyyy-MM-dd`; empty when the timestamp is unknown. */
fun formatDownloadDate(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
}
