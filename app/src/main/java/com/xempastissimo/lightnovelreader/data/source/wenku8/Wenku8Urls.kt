package com.xempastissimo.lightnovelreader.data.source.wenku8

import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.domain.model.SearchField

/**
 * Every URL the source exposes, in one place.
 *
 * Verified against the live site (see README "书源映射"): the public pages are
 * the book detail page, the chapter list (目录) and a chapter body; rankings,
 * catalog and search all require a logged-in session and redirect to
 * `/login.php?jumpurl=...` otherwise.
 */
object Wenku8Urls {

    const val SCHEME = "https://"
    const val HOST = "www.wenku8.net"
    const val IMAGE_HOST = "http://img.wenku8.com"
    const val BASE = SCHEME + HOST

    /** Last-resort category when a book's category segment is unknown. */
    private const val DEFAULT_CATEGORY = "1"

    const val INDEX = "$BASE/index.php"
    const val LOGIN = "$BASE/login.php"
    const val LOGOUT = "$BASE/logout.php"
    const val USER_DETAIL = "$BASE/userdetail.php"
    /**
     * The user's bookshelf, **default group only**.
     *
     * The site also splits the shelf into groups reachable as `?classid=1` … `?classid=5`
     * — six groups including this default one. The app does not present groups yet, so
     * it reads and manages just this one; supporting them means parameterising this URL
     * and fetching each group in turn.
     */
    const val BOOKCASE = "$BASE/modules/article/bookcase.php"
    const val RESET_BOOKCASE_ORDER = "$BASE/modules/article/bookcase.php?do=order"
    const val ARTICLE_LIST = "$BASE/modules/article/articlelist.php"
    const val TOP_LIST = "$BASE/modules/article/toplist.php"
    const val SEARCH = "$BASE/modules/article/search.php"
    const val TAGS = "$BASE/modules/article/tags.php"
    const val REVIEWS = "$BASE/modules/article/reviewslist.php"

    const val USER_INFO_COOKIE = "jieqiUserInfo"
    const val SESSION_COOKIE = "PHPSESSID"

    /**
     * Values of the login form's 有效期 select, as the page actually renders
     * them (a number of seconds, not a small enum).
     */
    const val COOKIE_BROWSER = "0"
    const val COOKIE_ONE_DAY = "86400"
    const val COOKIE_ONE_MONTH = "2592000"
    const val COOKIE_ONE_YEAR = "315360000"

    fun book(bookId: Int): String = "$BASE/book/$bookId.htm"

    /** Catalog of a book; the URL carries its category segment (`/novel/1/1973/`). */
    fun chapterList(bookId: Int, novelPrefix: String?): String =
        if (novelPrefix.isNullOrBlank()) "$BASE/modules/article/articleinfo.php?id=$bookId"
        else "$BASE/${novelPrefix.trim('/')}/index.htm"

    fun chapter(novelPrefix: String, chapterId: Int): String =
        "$BASE/${novelPrefix.trim('/')}/$chapterId.htm"

    /**
     * Cover thumbnail of a book.
     *
     * Verified against the live image host: the path is
     * `…/image/{cat}/{aid}/{aid}s.jpg` — the middle segment is the book id itself
     * (there is **no** id sharding), and `cat` is the category segment of the
     * book's `/novel/{cat}/{aid}/…` URLs, which is the only place it appears.
     */
    fun cover(bookId: Int, category: String): String {
        val cat = category.ifBlank { DEFAULT_CATEGORY }
        return "$IMAGE_HOST/image/$cat/$bookId/${bookId}s.jpg"
    }

    /**
     * Cover of a book whose page carries no cover image, derived from one of its
     * chapter / catalogue URLs (`/novel/{cat}/{aid}/…`).
     *
     * Returns null when the URL has no category segment, so the caller can fall
     * back to its own placeholder instead of requesting a URL that cannot exist.
     */
    fun coverFromNovelUrl(bookId: Int, novelUrl: String?): String? =
        categoryOf(novelUrl)?.let { cover(bookId, it) }

    /** Category segment (`cat`) of a `/novel/{cat}/{aid}/…` URL. */
    fun categoryOf(url: String?): String? =
        novelPrefixOf(url)?.trim('/')?.split('/')?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** Detail page's own cover when present; falls back to the deterministic path. */
    fun coverFromPage(rawSrcUrl: String?): String? {
        if (rawSrcUrl.isNullOrBlank()) return null
        return when {
            rawSrcUrl.startsWith("https://") -> rawSrcUrl
            rawSrcUrl.startsWith("http://") -> rawSrcUrl
            rawSrcUrl.startsWith("//") -> "https:$rawSrcUrl"
            rawSrcUrl.startsWith("/") -> BASE + rawSrcUrl
            else -> rawSrcUrl
        }
    }

    fun rank(type: RankType, page: Int = 1): String {
        val base = when (type) {
            RankType.FULL_FLAG -> "$ARTICLE_LIST?fullflag=1"
            else -> "$TOP_LIST?sort=${type.sort}"
        }
        val separator = if (base.contains('?')) '&' else '?'
        return if (page <= 1) base else "$base${separator}page=$page"
    }

    fun catalog(page: Int = 1, fullFlag: Boolean = false, category: Int? = null): String {
        val params = ArrayList<String>(3)
        if (fullFlag) params.add("fullflag=1")
        if (category != null && category > 0) params.add("sort=$category")
        params.add("page=$page")
        return "$ARTICLE_LIST?" + params.joinToString("&")
    }

    fun search(keyword: String, field: SearchField): String {
        val encoded = java.net.URLEncoder.encode(keyword, "GBK")
        return "$SEARCH?searchtype=${field.value}&searchkey=$encoded"
    }

    fun loginPost(jumpUrl: String = INDEX): String =
        "$LOGIN?do=submit&jumpurl=" + java.net.URLEncoder.encode(jumpUrl, "UTF-8")

    /**
     * Removes one book from the bookshelf.
     *
     * [shelfId] is the bookshelf's **own** id — the `bid` in a `readbookcase.php?aid=…&bid=…`
     * link and the `value` of the row's checkbox, e.g. `13066825`. It is deliberately not
     * an `Int` book id: that is the `aid` this app uses everywhere else (e.g. `3988`), and
     * a different number for the same book. This endpoint only accepts the former.
     *
     * This is exactly where the page's own 移除 control navigates:
     * `document.location='/modules/article/bookcase.php?delid=13066825'`.
     */
    fun removeFromBookcase(shelfId: String): String = "$BOOKCASE?delid=$shelfId"

    fun addToBookcase(bookId: Int): String =
        "$BASE/modules/article/addbookcase.php?bid=$bookId"

    /** Resolves a page-relative link the way a browser would; null when there is none. */
    fun absoluteUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        return when {
            href.startsWith("https://") || href.startsWith("http://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> BASE + href
            else -> "$BASE/$href"
        }
    }

    private val BOOK_ID = Regex("""/book/(\d+)\.htm""")

    /**
     * Matches both URL shapes that carry a book's category segment:
     * - a chapter URL: `/novel/1/1973/176596.htm`
     * - the catalogue page the detail page's 阅读 button links to:
     *   `/novel/3/3988/index.htm`
     *
     * It matches rooted paths and absolute URLs alike.
     */
    private val NOVEL_PREFIX = Regex(""".*?(/novel/\d+/\d+/)(?:index|\d+)\.htm$""")
    private val BOOKCASE_AID = Regex("""[?&]aid=(\d+)""")

    fun bookIdOf(url: String?): Int? = url?.let { BOOK_ID.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Book id from a bookcase link (`readbookcase.php?aid=...&bid=...`). */
    fun bookIdOfBookcase(url: String?): Int? =
        url?.let { BOOKCASE_AID.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Chapter id from a bookcase link when it points at the last chapter. */
    fun chapterIdOfBookcase(url: String?): Int? =
        url?.let { Regex("""[?&]cid=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * `/novel/1/1973/176596.htm` -> `/novel/1/1973/`, and the same for the
     * catalogue page `/novel/3/3988/index.htm`.
     */
    fun novelPrefixOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        return NOVEL_PREFIX.find(withoutQuery)?.groupValues?.get(1)
    }

    /** True when the URL is a chapter body (`…/{cid}.htm`), not a catalogue page. */
    fun isChapterUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        return Regex("""/novel/\d+/\d+/\d+\.htm$""").containsMatchIn(withoutQuery)
    }

    /** Accepts a bare id, a detail URL or a chapter URL. */
    fun normalizeBookInput(raw: String): Int? {
        val trimmed = raw.trim()
        trimmed.toIntOrNull()?.let { return it }
        bookIdOf(trimmed)?.let { return it }
        val novel = Regex("""/novel/\d+/(\d+)/""").find(trimmed)
        return novel?.groupValues?.get(1)?.toIntOrNull()
    }
}
