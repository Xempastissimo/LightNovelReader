package com.xempastissimo.lightnovelreader.data.source.wenku8

import android.util.Log
import com.xempastissimo.lightnovelreader.core.html.Html
import com.xempastissimo.lightnovelreader.core.text.CharsetCodec
import com.xempastissimo.lightnovelreader.core.text.TextCleaner
import com.xempastissimo.lightnovelreader.data.network.BrowserBackedFetcher
import com.xempastissimo.lightnovelreader.data.network.CookieStore
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.network.HttpFetcher
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.data.source.LoginResult
import com.xempastissimo.lightnovelreader.data.source.OnlineShelf
import com.xempastissimo.lightnovelreader.data.source.PackedBook
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.RankType
import com.xempastissimo.lightnovelreader.domain.model.SearchField
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.domain.model.UserSession
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * The first book source: 轻小说文库 (wenku8).
 *
 * Notes that shaped the implementation:
 * - the site serves GBK pages, so every request goes through the charset-aware
 *   [HttpFetcher] rather than reading strings directly;
 * - rankings, catalog, search and the online bookshelf require a login and
 *   redirect to `/login.php?jumpurl=...` otherwise, which surfaces as
 *   [HttpFailure.AuthRequired] so the UI can prompt instead of showing garbage;
 * - chapters live under `/novel/{category}/{bookId}/{chapterId}.htm`; the
 *   category segment is discovered from the detail page and carried in
 *   [BookDetail.novelPrefix].
 */
class Wenku8Source(
    private val http: HttpFetcher,
    private val cookies: CookieStore,
    private val minIntervalMillis: Long = 900L,
) : BookSource {

    override val id: String = "wenku8"

    override val displayName: String = "轻小说文库"

    override fun isLoggedIn(): Boolean = cookies.isLoggedIn()

    override fun imageReferer(): String = Wenku8Urls.BASE

    override fun currentSession(): UserSession? {
        val raw = cookies.valueOf(Wenku8Urls.USER_INFO_COOKIE) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val fields = decoded.split(',')
            .mapNotNull { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) null else entry.substring(0, index).trim() to entry.substring(index + 1).trim()
            }
            .toMap()
        val session = UserSession(
            userId = fields["jieqiUserId"].orEmpty(),
            userName = unescapeEntities(fields["jieqiUserName"].orEmpty()),
            groupName = unescapeEntities(fields["jieqiUserGroupName_un"].orEmpty()),
            honorName = unescapeEntities(fields["jieqiUserHonor_un"].orEmpty()),
            loggedInAt = fields["jieqiUserLogin"]?.toLongOrNull()?.times(1000) ?: 0L,
        )
        return session.takeIf { it.isLoggedIn }
    }

    /** Cookie payloads store Chinese names as `&#x...;` sequences. */
    private fun unescapeEntities(value: String): String = Html.decodeEntities(value)

    override suspend fun login(userName: String, password: String, keepDays: Int): LoginResult {
        if (!isLoggedIn()) {
            // Establish a session first: the source refuses a login POST with no cookie.
            runCatching { http.getText(Wenku8Urls.LOGIN) }
        }
        val useCookie = when {
            keepDays <= 0 -> Wenku8Urls.COOKIE_BROWSER
            keepDays <= 1 -> Wenku8Urls.COOKIE_ONE_DAY
            keepDays <= 31 -> Wenku8Urls.COOKIE_ONE_MONTH
            else -> Wenku8Urls.COOKIE_ONE_YEAR
        }
        val form = mapOf(
            "username" to userName,
            "password" to password,
            "usecookie" to useCookie,
            "action" to "login",
        )
        val response = runCatching {
            http.postForm(Wenku8Urls.loginPost(), form, referer = Wenku8Urls.LOGIN)
        }.getOrElse { error ->
            return when (error) {
                is HttpFailure -> LoginResult.Failure(error.message ?: "登录请求失败")
                else -> LoginResult.Failure("登录请求失败：${error.message}")
            }
        }

        if (response.contains("checkcode") || response.contains("验证码")) {
            return LoginResult.NeedCaptcha("${Wenku8Urls.BASE}/checkcode.php")
        }

        val session = currentSession()
        if (session != null) return LoginResult.Success(session)

        // The cookie can lag behind; confirm by looking for the logout link.
        val home = runCatching { http.getText(Wenku8Urls.INDEX) }.getOrDefault("")
        val confirmed = currentSession()
        if (confirmed != null || home.contains("logout.php")) {
            return LoginResult.Success(
                confirmed ?: UserSession(userName = userName, loggedInAt = System.currentTimeMillis()),
            )
        }
        return LoginResult.Failure("用户名或密码不正确")
    }

    override fun logout() {
        // Best effort: drop the local session. The remote session expires on its own.
        cookies.clear()
    }

    override suspend fun rank(type: RankType, page: Int): List<Book> =
        requireLogin {
            val html = http.getText(Wenku8Urls.rank(type, page), referer = Wenku8Urls.INDEX)
            Wenku8Parser.parseRankingList(html).ifEmpty { Wenku8Parser.parseBookList(html) }
        }

    override suspend fun catalog(page: Int, fullOnly: Boolean): List<Book> =
        requireLogin {
            val html = http.getText(Wenku8Urls.catalog(page = page, fullFlag = fullOnly))
            Wenku8Parser.parseRankingList(html).ifEmpty { Wenku8Parser.parseBookList(html) }
        }

    override suspend fun search(keyword: String, field: SearchField): List<Book> {
        if (keyword.isBlank()) return emptyList()
        return requireLogin {
            // The search form is GBK-encoded, which [Wenku8Urls.search] handles.
            val html = http.getText(Wenku8Urls.search(keyword, field), referer = Wenku8Urls.INDEX)
            Wenku8Parser.parseRankingList(html).ifEmpty { Wenku8Parser.parseBookList(html) }
        }
    }

    override suspend fun recentUpdates(): List<Book> {
        val home = http.getText(Wenku8Urls.INDEX)
        val parsed = Wenku8Parser.parseRecentUpdates(home)
        if (parsed.isNotEmpty()) return parsed

        // A blank list usually means the request landed on the login form or on the
        // bot-protection interstitial rather than the home page, which is far more
        // useful to report than "no entries".
        if (BrowserBackedFetcher.isChallengePage(home)) {
            throw HttpFailure.Challenge(
                "站点返回了浏览器校验页面。请到「设置 → 账号 → 使用浏览器登录」完成校验后重试。",
            )
        }
        if (Wenku8Parser.looksLikeLoginPage(home)) {
            throw HttpFailure.AuthRequired("书源要求登录后才能读取榜单与最近更新")
        }
        Log.w(
            TAG,
            "home page produced no entries: chars=${home.length} title=${Wenku8Parser.pageTitle(home)}",
        )
        return emptyList()
    }

    /**
     * Detail + catalogue in one pass.
     *
     * The public [chapterList] reuses this instead of fetching the detail page
     * again: two page loads per book is already the minimum here (detail +
     * catalogue), and the source rate-limits aggressively.
     */
    override suspend fun detail(bookId: Int): BookDetail = loadDetailAndChapters(bookId)

    /**
     * The detail page alone.
     *
     * Everything a shelf row shows — cover, 文库分类, 文章状态, 最后更新 — is on this one page;
     * the catalogue is a second load the caller here does not need. Used to fill in books the
     * site's own bookshelf lists as nothing but a title and an author (see
     * `ShelfViewModel.backfillMissingMetadata`).
     */
    override suspend fun bookSummary(bookId: Int): Book {
        val html = http.getText(Wenku8Urls.book(bookId), referer = Wenku8Urls.INDEX)
        return Wenku8Parser.parseBookDetail(html, bookId)?.book
            ?: throw HttpFailure.Status(200, "无法解析书籍信息（页面结构可能已变更）")
    }

    override suspend fun chapterList(bookId: Int): BookDetail = loadDetailAndChapters(bookId)

    private suspend fun loadDetailAndChapters(bookId: Int): BookDetail {
        val html = http.getText(Wenku8Urls.book(bookId), referer = Wenku8Urls.INDEX)
        val detail = Wenku8Parser.parseBookDetail(html, bookId)
            ?: throw HttpFailure.Status(200, "无法解析书籍信息（页面结构可能已变更）")
        if (detail.novelPrefix.isBlank()) {
            throw HttpFailure.Status(200, "该书未提供在线目录")
        }
        val tocUrl = Wenku8Urls.chapterList(bookId, detail.novelPrefix)
        val tocHtml = http.getText(tocUrl, referer = Wenku8Urls.book(bookId))
        val volumes = Wenku8Parser.parseChapterList(tocHtml, bookId)
        if (volumes.isEmpty()) {
            // Distinguish "page not fetched" from "page fetched but not parsed":
            // the caller needs to know which one to act on.
            when {
                BrowserBackedFetcher.isChallengePage(tocHtml) ->
                    throw HttpFailure.Challenge("读取目录时被站点校验拦截，请完成浏览器校验后重试")

                Wenku8Parser.looksLikeLoginPage(tocHtml) ->
                    throw HttpFailure.AuthRequired("该书目录需要登录后才能读取")

                else -> Log.w(
                    TAG,
                    "catalogue page parsed to zero volumes: url=$tocUrl chars=${tocHtml.length} " +
                        "title=${Wenku8Parser.pageTitle(tocHtml)}",
                )
            }
        }
        return detail.copy(
            volumes = volumes,
            book = detail.book.copy(
                latestChapter = detail.book.latestChapter.ifBlank {
                    volumes.lastOrNull()?.chapters?.lastOrNull()?.title.orEmpty()
                },
            ),
        )
    }

    override suspend fun content(bookId: Int, chapterId: Int, fallbackTitle: String): ChapterContent {
        val prefix = resolveNovelPrefix(bookId)
        val html = http.getText(
            Wenku8Urls.chapter(prefix, chapterId),
            referer = Wenku8Urls.chapterList(bookId, prefix),
        )
        val parsed = Wenku8Parser.parseChapterContent(html, bookId, chapterId, fallbackTitle)
        return parsed.copy(blocks = Wenku8Parser.normalizeBlocks(parsed.blocks))
    }

    override val supportsPackDownload: Boolean = true

    /**
     * The site's own whole-book pack download.
     *
     * Deliberately built from [Wenku8Urls.packDownload] instead of reading
     * `packshow.php`: that page needs a login, is challenged for non-browser clients, and
     * carries nothing the URL does not — the links it shows are this URL with `type` and
     * `node` varied. The download host is a plain file host, so this is the one part of
     * the source that goes through the fast native channel instead of the browser engine.
     *
     * The mirrors and encodings are tried in the order the site itself suggests (载点一,
     * then 载点二) for UTF-8 first and GBK second: UTF-8 is what this app reads natively
     * and is the same text, only larger. Nothing is skipped past silently — if every
     * candidate fails, the last failure is what the user is told about.
     */
    override suspend fun downloadPack(bookId: Int, detail: BookDetail): PackedBook {
        var lastFailure: Throwable? = null
        val referer = Wenku8Urls.book(bookId)

        for (candidate in PACK_CANDIDATES) {
            val url = Wenku8Urls.packDownload(bookId, candidate.type, candidate.node)
            val bytes = try {
                http.getBytes(url, referer = referer).bytes
            } catch (error: Throwable) {
                // A 404 is a fact about the book, not about the mirror: the pack has not
                // been generated (or the book is gone), and the other three candidates
                // would answer exactly the same way.
                if (error is HttpFailure.Status && error.statusCode == 404) {
                    throw HttpFailure.Status(404, "站点没有提供这本书的打包下载（打包文件可能尚未生成）")
                }
                lastFailure = error
                Log.w(TAG, "pack download failed: $url -> ${error.message}")
                continue
            }

            if (bytes.isEmpty()) {
                lastFailure = HttpFailure.Status(200, "下载到的打包文件是空的")
                continue
            }

            val sections = Wenku8PackParser.scan(bytes, candidate.charset)
            if (sections.isEmpty()) {
                lastFailure = HttpFailure.Status(200, "打包文件里没有解析出任何章节")
                continue
            }

            val match = Wenku8PackParser.match(detail, sections)
            Log.i(
                TAG,
                "pack for $bookId: url=$url bytes=${bytes.size} charset=${candidate.charset.name()} " +
                    "sections=${sections.size} covered=${match.coveredChapters} unmatched=${match.unmatchedHeadings}",
            )
            return PackedBook(
                bytes = bytes,
                charsetName = candidate.charset.name(),
                sourceUrl = url,
                slices = match.slices,
                coveredChapters = match.coveredChapters,
                unmatchedHeadings = match.unmatchedHeadings,
            )
        }

        throw (lastFailure as? HttpFailure)
            ?: HttpFailure.Network("整本下载失败：${lastFailure?.message ?: "未知错误"}", lastFailure)
    }

    override val onlineShelfCapacity: Int = MAX_BOOKCASE_BOOKS

    /**
     * The user's bookshelf.
     *
     * Only the default group is read. The site also splits the shelf into
     * `bookcase.php?classid=1…5`, but the app does not surface groups yet, so the default
     * group is what it lists. The totals, though, are taken from the page's own header:
     * they cover every group, while the table only covers this one.
     */
    override suspend fun onlineShelf(): OnlineShelf = requireLogin {
        val html = http.getText(Wenku8Urls.BOOKCASE, referer = Wenku8Urls.INDEX)
        val books = Wenku8Parser.parseBookcase(html)
        val summary = Wenku8Parser.parseBookcaseSummary(html)
        OnlineShelf(
            entries = books.mapIndexed { index, book ->
                ShelfEntry(book = book, addedAt = (books.size - index).toLong())
            },
            totalCount = summary?.total ?: books.size,
            capacity = summary?.capacity ?: onlineShelfCapacity,
            inGroupCount = summary?.inGroup ?: books.size,
        )
    }

    override suspend fun addToOnlineShelf(bookId: Int): Boolean = requireLogin {
        val url = resolveActionUrl(
            pageUrl = Wenku8Urls.book(bookId),
            selector = Wenku8Selectors.LIST_ONLINE_SHELF_ADD,
            mustContain = "addbookcase",
            fallback = Wenku8Urls.addToBookcase(bookId),
        )
        accepted(http.getText(url, referer = Wenku8Urls.book(bookId)))
    }

    override suspend fun removeFromOnlineShelf(bookId: Int): Boolean =
        removeFromOnlineShelf(listOf(bookId))

    /**
     * Removes books from the source's own bookshelf.
     *
     * Two facts about the live page shape this:
     *
     * - there is no per-row delete *link*. The 移除 control is a `javascript:`
     *   `document.location` call, so the removal has to be driven from the ids read off the
     *   page — constructing a link is what made the earlier implementation a silent no-op;
     * - the bookshelf identifies a row by its own `bid`, which is a *different* number
     *   from the `aid` this app uses as a book id. Only `bid` is accepted, so the pairing
     *   is read from the page's checkboxes first.
     *
     * **Every** removal — one book or ten — is the row's own `bookcase.php?delid={bid}` GET.
     * The page's bulk form would be one request instead of N, but it can only be submitted as
     * a POST, and this app's POST channel is the plain `HttpURLConnection` client that the site
     * answers with `403` (Cloudflare checks the TLS fingerprint — see README §8.1). That is
     * exactly the bug this replaced: picking several rows and deleting them reported
     * 「书源拒绝访问（403）」 while deleting one row worked, because only the single-book path
     * used a GET. Removing *one* row at a time is a request shape the site accepts, and it is
     * the same operation its own 移除 control performs.
     *
     * Sequential, like everything else against this source: every request goes through the
     * shared [RateLimiter], so removing several books is deliberately slower than the site's
     * own bulk form would be.
     */
    override suspend fun removeFromOnlineShelf(bookIds: Collection<Int>): Boolean = requireLogin {
        if (bookIds.isEmpty()) return@requireLogin true

        val html = http.getText(Wenku8Urls.BOOKCASE, referer = Wenku8Urls.INDEX)
        val rowIds = Wenku8Parser.parseBookcaseRowIds(html)
        val shelfIds = bookIds.mapNotNull { rowIds[it] }
        if (shelfIds.isEmpty()) return@requireLogin false

        var allAccepted = true
        for (shelfId in shelfIds) {
            // Exactly where that row's own 移除 control navigates.
            val response = http.getText(
                Wenku8Urls.removeFromBookcase(shelfId),
                referer = Wenku8Urls.BOOKCASE,
            )
            if (!accepted(response)) allAccepted = false
        }
        allAccepted
    }

    /**
     * Reads one of the source's own action links instead of constructing it.
     *
     * The paths for *reading* the site are documented and verified; the ones that
     * *change* a bookshelf are not, and the site builds them itself — `?bid=` today,
     * something else tomorrow. So the link is taken verbatim from a page that lists the
     * book in question, and [fallback] is used only when that page carries no such
     * link. A failure to fetch the listing page is not an error here: the fallback is
     * tried either way.
     */
    private suspend fun resolveActionUrl(
        pageUrl: String,
        selector: String,
        mustContain: String,
        fallback: String,
    ): String {
        val html = runCatching { http.getText(pageUrl, referer = Wenku8Urls.INDEX) }.getOrNull()
            ?: return fallback
        val href = Html.parse(html)
            .select(selector)
            .mapNotNull { it.attr("href") }
            .firstOrNull { it.contains(mustContain) && it.isNotBlank() }
            ?: return fallback
        return absoluteUrl(href)
    }

    private fun absoluteUrl(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> Wenku8Urls.BASE + href
        else -> "${Wenku8Urls.BASE}/$href"
    }

    /**
     * Whether the source honoured a bookshelf change.
     *
     * Adding or removing is a GET whose reply is another page rather than a status, so
     * the only honest signal available is that the reply is *not* one of the source's
     * refusals. This stays deliberately conservative — it reports "not confirmed"
     * rather than claiming a success the app cannot actually see, and the caller keeps
     * the local change either way.
     */
    private fun accepted(html: String): Boolean =
        !BrowserBackedFetcher.isChallengePage(html) &&
            !Wenku8Parser.looksLikeLoginPage(html) &&
            !html.contains("请先登录")

    /**
     * The category segment only appears in chapter URLs. It is cached per book so
     * opening the reader does not cost an extra page load.
     */
    private val prefixCache = HashMap<Int, String>()

    private suspend fun resolveNovelPrefix(bookId: Int): String {
        prefixCache[bookId]?.let { return it }
        val detail = Wenku8Parser.parseBookDetail(
            http.getText(Wenku8Urls.book(bookId), referer = Wenku8Urls.INDEX),
            bookId,
        )
        val prefix = detail?.novelPrefix.orEmpty()
        if (prefix.isNotBlank()) prefixCache[bookId] = prefix
        return prefix
    }

    fun cacheNovelPrefix(bookId: Int, prefix: String) {
        if (prefix.isNotBlank()) prefixCache[bookId] = prefix
    }

    private inline fun <T> requireLogin(block: () -> T): T {
        if (!isLoggedIn()) {
            throw HttpFailure.AuthRequired("该功能需要登录${displayName}账号")
        }
        return block()
    }

    companion object {
        private const val TAG = "Wenku8Source"

        /** The site's own cap on how many books a bookshelf may hold. */
        const val MAX_BOOKCASE_BOOKS = 300

        private val GBK = CharsetCodec.charsetFor(CharsetCodec.DEFAULT_SOURCE_CHARSET) ?: Charsets.UTF_8

        /** One `type`/`node` pair of the download page, and the charset that `type` means. */
        private class PackCandidate(val type: String, val node: Int, val charset: Charset)

        /**
         * What to try, in order: the site's own advice is to fall back from 载点一 to
         * 载点二, and UTF-8 is preferred over GBK because it is the same text in the
         * encoding this app reads natively.
         */
        private val PACK_CANDIDATES = listOf(
            PackCandidate(Wenku8Urls.PACK_UTF8, Wenku8Urls.PACK_NODE_PRIMARY, Charsets.UTF_8),
            PackCandidate(Wenku8Urls.PACK_UTF8, Wenku8Urls.PACK_NODE_MIRROR, Charsets.UTF_8),
            PackCandidate(Wenku8Urls.PACK_GBK, Wenku8Urls.PACK_NODE_PRIMARY, GBK),
            PackCandidate(Wenku8Urls.PACK_GBK, Wenku8Urls.PACK_NODE_MIRROR, GBK),
        )

        fun create(http: HttpFetcher, cookies: CookieStore): Wenku8Source =
            Wenku8Source(http, cookies)

        /** Convenience for logging: trims a page down to a short label. */
        fun summarize(html: String): String = TextCleaner.oneLine(html).take(80)
    }
}
