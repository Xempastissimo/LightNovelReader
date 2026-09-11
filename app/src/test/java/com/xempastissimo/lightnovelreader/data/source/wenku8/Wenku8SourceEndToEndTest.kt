package com.xempastissimo.lightnovelreader.data.source.wenku8

import com.xempastissimo.lightnovelreader.data.network.CookieStore
import com.xempastissimo.lightnovelreader.data.network.HttpBytes
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.network.HttpFetcher
import com.xempastissimo.lightnovelreader.data.network.HttpPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * End-to-end test of the book source with the HTTP layer replaced.
 *
 * This separates the two things that were previously conflated when debugging:
 * whether the HTML is parsed correctly, and whether the site can be reached at
 * all. The fixtures mirror the real markup's tags/classes/nesting with neutral
 * text; the bytes are GBK-encoded like the live pages.
 *
 * If this test passes, a `403 Cf-Mitigated=[challenge]` in a device log is a
 * network/IP-level block and not a parsing defect.
 */
class Wenku8SourceEndToEndTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Serves canned pages, keyed by the URL suffixes the source actually requests. */
    private class FakeFetcher(private val pages: Map<String, String>) : HttpFetcher {

        val requested = ArrayList<String>()

        /** Every form posted, so a test can assert the request the source actually makes. */
        val posted = ArrayList<Pair<String, List<Pair<String, String>>>>()

        private fun pageFor(url: String): String? =
            pages.entries.firstOrNull { (key, _) -> url.endsWith(key) || url.contains(key) }?.value

        override suspend fun getText(
            url: String,
            referer: String?,
            extraHeaders: Map<String, String>,
            charsetOverride: Charset?,
        ): String {
            requested.add(url)
            return pageFor(url) ?: error("FakeFetcher has no page for $url")
        }

        override suspend fun getPage(url: String, referer: String?, charsetOverride: Charset?): HttpPage =
            HttpPage(url, url, 200, getText(url, referer, emptyMap(), charsetOverride), emptyMap())

        override suspend fun getBytes(url: String, referer: String?): HttpBytes =
            HttpBytes(url, 200, ByteArray(0), "image/jpeg")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            referer: String?,
            charset: Charset,
        ): String = postForm(url, form.entries.map { it.key to it.value }, referer, charset)

        override suspend fun postForm(
            url: String,
            fields: List<Pair<String, String>>,
            referer: String?,
            charset: Charset,
        ): String {
            requested.add(url)
            posted.add(url to fields)
            return pageFor(url) ?: ""
        }
    }

    private val detailHtml = """
        <html><head><title>测试小说 - 作者甲 - 测试文库 - 轻小说文库</title></head>
        <body><div id="content"><div>
          <table width="100%">
            <tr><td colspan="2"><a href="/modules/article/uservote.php?id=3988">测试小说[推一下!]</a></td></tr>
          </table>

          <!-- The 阅读 fieldset, exactly the shape reported from the live site. -->
          <span style="width:145px;display:inline-block;">
            <fieldset style="width:100px;height:35px;margin:0px;padding:0px;">
              <legend>阅读</legend>
              <div style="text-align:center"><a href="/novel/3/3988/index.htm">小说目录</a></div>
            </fieldset>
          </span>

          <table width="100%">
            <tr><td>
              <span class="hottext"><b>作品Tags：标签一 标签二</b></span><br>
              <span class="hottext">最近章节：</span><br>
              <span><a href="/novel/3/3988/222222.htm">第二卷 章名乙</a></span><br><br>
              <span class="hottext">内容简介：</span><br>
              <span>简介第一行<br>简介第二行</span><br>
            </td></tr>
          </table>

          <table width="100%">
            <tr><td><img src="http://img.wenku8.com/image/3/3988/3988s.jpg" width="168"></td></tr>
            <tr><td>文库分类：测试文库</td><td>小说作者：作者甲</td></tr>
            <tr><td>文章状态：连载中</td><td>最后更新：2026-01-02</td></tr>
          </table>
        </div></div></body></html>
    """.trimIndent()

    private val tocHtml = """
        <html><body><div id="content">
        <table class="css">
          <tr><td class="vcss" colspan="2">第一卷 卷名甲</td></tr>
          <tr><td class="ccss"><a href="111111.htm">第一章 章名一</a></td>
              <td class="ccss"><a href="111112.htm">第二章 章名二</a></td></tr>
          <tr><td class="vcss" colspan="2">第二卷 卷名乙</td></tr>
          <tr><td class="ccss"><a href="222222.htm">第一章 章名三</a></td></tr>
        </table>
        </div></body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><div id="content">
          <ul id="contentdp"><li><a href="/book/3988.htm">测试小说</a></li><li>第一章 章名一</li></ul>
          正文第一段<br>
          正文第二段<br>
          <img src="/pictures/3/3988/111111/1.jpg" border="0"><br>
          正文第三段<br>
          <ul id="contentdp"><li><a href="111100.htm">上一章</a></li>
          <li><a href="111112.htm">下一章</a></li></ul>
        </div></body></html>
    """.trimIndent()

    private fun sourceWith(pages: Map<String, String>): Pair<Wenku8Source, FakeFetcher> {
        val fetcher = FakeFetcher(pages)
        val cookies = CookieStore(File(folder.root, "cookies.json"))
        return Wenku8Source(fetcher, cookies) to fetcher
    }

    /** A source whose cookie jar already holds the source's session cookie. */
    private fun loggedInSourceWith(pages: Map<String, String>): Pair<Wenku8Source, FakeFetcher> {
        val fetcher = FakeFetcher(pages)
        val cookies = CookieStore(File(folder.root, "cookies.json"))
        // Presence of `jieqiUserInfo` is what the source treats as a session.
        cookies.save(Wenku8Urls.BASE, listOf("jieqiUserInfo=jieqiUserName%3Dtester; path=/"))
        return Wenku8Source(fetcher, cookies) to fetcher
    }

    /**
     * Two rows of the real bookshelf, reduced to what removal depends on.
     *
     * The ids matter: `aid` (3988) is the book id used by every other page, while the
     * checkbox and the 移除 control carry the shelf's own `bid` (13066825).
     */
    private val bookcaseHtml = """
        <html><body><div id="content">
        <form action="" method="post" name="checkform" id="checkform">
        <div class="gridtop">您的书架可收藏 300 本，已收藏 2 本，本组有 2 本。</div>
        <table class="grid"><tbody>
          <tr>
            <td class="odd"><input type="checkbox" id="checkid[]" name="checkid[]" value="13066825"></td>
            <td class="even"><a href="/modules/article/readbookcase.php?aid=3988&amp;bid=13066825">甲</a></td>
            <td class="odd"><a href="authorarticle.php?author=作者甲">作者甲</a></td>
            <td class="even"><a href="/modules/article/readbookcase.php?aid=3988&amp;bid=13066825&amp;cid=178729">最新章</a></td>
            <td class="even"><a href="javascript:confirm('x');document.location='/modules/article/bookcase.php?delid=13066825';">移除</a></td>
          </tr>
          <tr>
            <td class="odd"><input type="checkbox" id="checkid[]" name="checkid[]" value="13078679"></td>
            <td class="even"><a href="/modules/article/readbookcase.php?aid=1973&amp;bid=13078679">乙</a></td>
            <td class="odd"><a href="authorarticle.php?author=作者乙">作者乙</a></td>
            <td class="even"><a href="/modules/article/readbookcase.php?aid=1973&amp;bid=13078679&amp;cid=176596">最新章二</a></td>
            <td class="even"><a href="javascript:confirm('x');document.location='/modules/article/bookcase.php?delid=13078679';">移除</a></td>
          </tr>
          <tr><td colspan="6" class="foot">选中项目
            <select name="newclassid" id="newclassid">
              <option value="-1" selected="selected">移出书架</option>
              <option value="0">移到默认书架</option>
            </select>
            <input name="btnsubmit" type="submit" value=" 确认 " class="button">
            <input name="clsssid" type="hidden" value="0">
          </td></tr>
        </tbody></table>
        </form>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `the shelf reports the totals the page states`() = runBlocking {
        val (source, _) = loggedInSourceWith(mapOf("/modules/article/bookcase.php" to bookcaseHtml))

        val shelf = source.onlineShelf()

        assertEquals(listOf(3988, 1973), shelf.entries.map { it.book.bookId })
        assertEquals("作者甲", shelf.entries[0].book.author)
        assertEquals(300, shelf.capacity)
        assertEquals(2, shelf.totalCount)
    }

    /**
     * The reported bug: removal never happened because the request was built from the
     * book id the rest of the app uses, and from a URL shape the bookshelf does not have.
     */
    @Test
    fun `removing one book follows the row's own delid link`() = runBlocking {
        val (source, fetcher) = loggedInSourceWith(mapOf("/modules/article/bookcase.php" to bookcaseHtml))

        assertTrue(source.removeFromOnlineShelf(3988))

        assertTrue(
            "expected the shelf's own row id, got ${fetcher.requested}",
            fetcher.requested.any { it.endsWith("bookcase.php?delid=13066825") },
        )
        assertFalse(
            "the book id (aid) is a different number and must not be sent as delid",
            fetcher.requested.any { it.contains("delid=3988") },
        )
    }

    /** Two or more at once go through the page's own bulk form, as its 确认 button does. */
    @Test
    fun `removing several books submits the page's own bulk form`() = runBlocking {
        val (source, fetcher) = loggedInSourceWith(mapOf("/modules/article/bookcase.php" to bookcaseHtml))

        assertTrue(source.removeFromOnlineShelf(listOf(3988, 1973)))

        val (url, fields) = fetcher.posted.single()
        assertEquals(Wenku8Urls.BOOKCASE, url)
        // `checkid[]` repeats once per ticked book — the reason postForm takes a list.
        assertEquals(
            listOf("13066825", "13078679"),
            fields.filter { it.first == "checkid[]" }.map { it.second },
        )
        assertTrue("the operation must be 移出书架", fields.contains("newclassid" to "-1"))
        assertTrue("the current group must be posted back", fields.contains("clsssid" to "0"))
        assertTrue(fields.contains("btnsubmit" to " 确认 "))
    }

    @Test
    fun `removal without a session is refused rather than silently doing nothing`() = runBlocking {
        val (source, _) = sourceWith(mapOf("/modules/article/bookcase.php" to bookcaseHtml))

        val error = runCatching { source.removeFromOnlineShelf(3988) }.exceptionOrNull()

        assertTrue("expected a login prompt, got $error", error is HttpFailure.AuthRequired)
    }

    @Test
    fun `a book the shelf does not list cannot be removed`() = runBlocking {
        val (source, fetcher) = loggedInSourceWith(mapOf("/modules/article/bookcase.php" to bookcaseHtml))

        // Not on this shelf page, so there is no row id to act on.
        assertFalse(source.removeFromOnlineShelf(999999))

        assertTrue("nothing should have been posted", fetcher.posted.isEmpty())
    }

    /**
     * The regression that made book 3988 unreadable: the 阅读 button links to the
     * catalogue page, so the source must still end up with the full chapter list.
     */
    @Test
    fun `detail resolves the chapter list through the catalogue link`() = runBlocking {
        val (source, fetcher) = sourceWith(
            mapOf(
                "/book/3988.htm" to detailHtml,
                "/novel/3/3988/index.htm" to tocHtml,
            ),
        )

        val detail = source.detail(3988)

        assertEquals("/novel/3/3988/", detail.novelPrefix)
        assertEquals(2, detail.volumes.size)
        assertEquals(3, detail.chapters.size)
        assertEquals(listOf(111111, 111112, 222222), detail.chapters.map { it.chapterId })
        assertEquals("测试小说", detail.book.title)
        assertEquals("作者甲", detail.book.author)
        assertEquals("测试文库", detail.book.category)
        assertEquals(listOf("标签一", "标签二"), detail.tags)

        // The catalogue page must have been requested at the URL the source derives.
        assertTrue(
            "expected the catalogue page to be fetched, got ${fetcher.requested}",
            fetcher.requested.any { it.endsWith("/novel/3/3988/index.htm") },
        )
    }

    @Test
    fun `chapter content is fetched from the derived prefix`() = runBlocking {
        val (source, fetcher) = sourceWith(
            mapOf(
                "/book/3988.htm" to detailHtml,
                "/novel/3/3988/index.htm" to tocHtml,
                "/novel/3/3988/111111.htm" to chapterHtml,
            ),
        )

        source.detail(3988)
        val content = source.content(3988, 111111, "第一章 章名一")

        assertEquals("第一章 章名一", content.title)
        assertFalse("a reachable chapter must not look like a login wall", content.requiresLogin)
        assertTrue(content.paragraphs.contains("正文第一段"))
        assertTrue(content.paragraphs.contains("正文第二段"))
        assertTrue(content.paragraphs.contains("正文第三段"))
        assertEquals(111100, content.previousChapterId)
        assertEquals(111112, content.nextChapterId)
        assertEquals(listOf("https://www.wenku8.net/pictures/3/3988/111111/1.jpg"), content.illustrations)

        assertTrue(
            "expected the chapter to be fetched under /novel/3/3988/, got ${fetcher.requested}",
            fetcher.requested.any { it.endsWith("/novel/3/3988/111111.htm") },
        )
    }

    /** A book whose detail page has no catalogue link at all. */
    @Test
    fun `missing catalogue link is reported instead of silently empty`() = runBlocking {
        val (source, _) = sourceWith(mapOf("/book/3988.htm" to "<html><body>nothing here</body></html>"))
        val error = runCatching { source.detail(3988) }.exceptionOrNull()
        assertNotNull("a book with no catalogue link must fail loudly", error)
    }

    @Test
    fun `gbk bytes decode through the whole pipeline`() = runBlocking {
        val gbk = Charset.forName("GBK")
        // The real pages are GBK; make sure nothing in the chain assumes UTF-8.
        val encoded = detailHtml.toByteArray(gbk)
        val decoded = com.xempastissimo.lightnovelreader.core.text.CharsetCodec.decode(encoded, null)
        assertTrue(decoded.contains("小说目录"))
        assertTrue(decoded.contains("/novel/3/3988/index.htm"))

        val (source, _) = sourceWith(
            mapOf(
                "/book/3988.htm" to decoded,
                "/novel/3/3988/index.htm" to tocHtml,
            ),
        )
        assertEquals(3, source.detail(3988).chapters.size)
    }
}
