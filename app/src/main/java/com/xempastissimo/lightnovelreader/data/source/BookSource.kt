package com.xempastissimo.lightnovelreader.data.source

import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.domain.model.SearchField
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.domain.model.UserSession

/** Result of a login attempt, kept explicit so the UI can explain a failure. */
sealed interface LoginResult {
    data class Success(val session: UserSession) : LoginResult

    /** The source wants a captcha / extra step this client cannot fabricate. */
    data class NeedCaptcha(val captchaUrl: String) : LoginResult

    data class Failure(val message: String) : LoginResult
}

/**
 * The user's bookshelf as the source reports it.
 *
 * The counts come from the page's own header rather than from the length of [entries]:
 * a bookshelf is paged and grouped, so the list is never the whole story.
 */
data class OnlineShelf(
    val entries: List<ShelfEntry> = emptyList(),
    /** How many books the account holds, in total. */
    val totalCount: Int = 0,
    /** How many the source will let it hold. */
    val capacity: Int = 0,
    /** How many are in the group [entries] was read from. */
    val inGroupCount: Int = 0,
)

/**
 * A byte range of a chapter's body inside a whole-book pack.
 *
 * Offsets are into [PackedBook.bytes] — the exact bytes written to disk — so the range
 * still addresses the same text after a restart, without decoding the whole pack again.
 */
data class PackSlice(val offset: Int, val length: Int)

/**
 * A whole-book text pack as the source publishes it, already matched against a catalogue.
 *
 * The bytes are kept verbatim (and recorded with [charsetName]) because the pack is an
 * artefact in its own right: it is what the source offered for download, and reading a
 * chapter back out of it must not depend on a re-download.
 */
class PackedBook(
    val bytes: ByteArray,
    /** Charset the pack was published in; the app decodes slices with it. */
    val charsetName: String,
    /** The URL this pack came from, kept for the record and for re-download messages. */
    val sourceUrl: String,
    /** Chapter id -> its body's byte range inside [bytes]; chapters the pack does not hold are absent. */
    val slices: Map<Int, PackSlice>,
    /** How many of the catalogue's chapters the pack covers. */
    val coveredChapters: Int,
    /** Headings the pack carries that no catalogue chapter claims (the pack lags or leads the site). */
    val unmatchedHeadings: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PackedBook &&
            charsetName == other.charsetName &&
            sourceUrl == other.sourceUrl &&
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + charsetName.hashCode()) * 31 + sourceUrl.hashCode()
}

/**
 * A book source: one website, reduced to the six operations the app needs.
 *
 * Adding a second source later means implementing this interface and registering
 * it in `AppContainer`; no screen or repository has to change.
 */
interface BookSource {

    val id: String
    val displayName: String

    /** `true` when the session cookie is present and the source accepted it. */
    fun isLoggedIn(): Boolean

    fun currentSession(): UserSession?

    suspend fun login(userName: String, password: String, keepDays: Int = 0): LoginResult

    fun logout()

    /** Ranking list (`sort=allvisit` and friends). */
    suspend fun rank(type: RankType, page: Int = 1): List<Book>

    /** Browse the whole catalog, optionally only completed works. */
    suspend fun catalog(page: Int = 1, fullOnly: Boolean = false): List<Book>

    suspend fun search(keyword: String, field: SearchField = SearchField.TITLE): List<Book>

    /** Homepage "最近更新" feed. */
    suspend fun recentUpdates(): List<Book>

    /** Metadata plus the volume/chapter tree. */
    suspend fun detail(bookId: Int): BookDetail

    /**
     * The book's own metadata, without its chapter tree.
     *
     * Separate from [detail] because the two have different costs and different callers: the
     * reader needs the tree and pays for both pages, while a list row that only wants a title,
     * a cover and a 文库 has no use for a catalogue. The default keeps a source that has no
     * cheaper path working; a source that does should override this.
     */
    suspend fun bookSummary(bookId: Int): Book = detail(bookId).book

    suspend fun chapterList(bookId: Int): BookDetail

    suspend fun content(bookId: Int, chapterId: Int, fallbackTitle: String = ""): ChapterContent

    /** The user's online bookshelf (requires a session on this source). */
    suspend fun onlineShelf(): OnlineShelf

    /**
     * How many books the source's own bookshelf will hold.
     *
     * Used until [onlineShelf] has been read once and reported the live figure.
     */
    val onlineShelfCapacity: Int

    suspend fun addToOnlineShelf(bookId: Int): Boolean

    suspend fun removeFromOnlineShelf(bookId: Int): Boolean

    /**
     * Removes several books at once.
     *
     * The default loops, so a source without a batch endpoint still satisfies the
     * contract; a source that has one overrides this and does it in a single operation.
     */
    suspend fun removeFromOnlineShelf(bookIds: Collection<Int>): Boolean {
        var allRemoved = true
        for (bookId in bookIds) {
            if (!removeFromOnlineShelf(bookId)) allRemoved = false
        }
        return allRemoved
    }

    /** Used by the cover/illustration loader for the source's image CDN. */
    fun imageReferer(): String

    /**
     * Whether the source publishes whole-book text packs.
     *
     * The screens ask before offering the download, because a source without packs must
     * not grow a button that can only fail. A source that has packs overrides both this
     * and [downloadPack].
     */
    val supportsPackDownload: Boolean get() = false

    /**
     * Downloads a book's whole-book text pack and matches it against [detail].
     *
     * The catalogue is passed in rather than fetched here: the caller has just loaded it
     * (the download is offered from the book's own page), and a second read of the same
     * catalogue would be one more page load against a site that throttles.
     *
     * The pack is a snapshot the source keeps for a while, so it can lag the live
     * catalogue: [PackedBook.slices] simply covers the chapters it holds. Returning
     * `null` means this source has no packs at all; a source that has them throws
     * [com.xempastissimo.lightnovelreader.data.network.HttpFailure] when this particular
     * book has none.
     */
    suspend fun downloadPack(bookId: Int, detail: BookDetail): PackedBook? =
        throw com.xempastissimo.lightnovelreader.data.network.HttpFailure.Status(
            501,
            "$displayName 未提供整本下载",
        )
}
