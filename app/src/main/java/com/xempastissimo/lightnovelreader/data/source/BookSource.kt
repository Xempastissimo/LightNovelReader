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
}
