package com.xempastissimo.lightnovelreader.data.source.wenku8

import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests driven by markup fragments that mirror the source's real DOM
 * shape (same tags, classes and nesting) but with neutral placeholder text.
 */
class Wenku8ParserTest {

    private val detailHtml = """
        <html><head><title>测试小说 - 作者甲 - 测试文库 - 轻小说文库</title></head>
        <body>
        <div id="content">
          <div>
            <table width="100%">
              <tr><td colspan="2"><a href="/modules/article/uservote.php?id=9999">测试小说[推一下!]</a></td></tr>
            </table>
            <table width="100%">
              <tr><td>
                <span class="hottext"><b>作品Tags：标签一 标签二</b></span><br>
                <span class="hottext"><b>作品热度：A级</b></span><br><br>
                <span class="hottext">最近章节：</span><br>
                <span><a href="/novel/1/9999/123456.htm">第三卷 第二章 章名乙</a></span><br><br>
                <span class="hottext">内容简介：</span><br>
                <span>简介第一行<br>简介第二行</span><br>
              </td></tr>
            </table>
            <br>
            <table width="100%">
              <tr><td><img src="http://img.wenku8.com/image/1/9999/9999s.jpg" width="168"></td></tr>
              <tr>
                <td>文库分类：测试文库</td>
                <td>小说作者：作者甲</td>
              </tr>
              <tr>
                <td>文章状态：连载中</td>
                <td>最后更新：2026-01-02</td>
              </tr>
            </table>
          </div>
        </div>
        </body></html>
    """.trimIndent()
    private val tocHtml = """
        <html><body><div id="content">
        <table class="css">
          <tr><td class="vcss" colspan="2">第一卷 卷名甲</td></tr>
          <tr><td class="ccss"><a href="/novel/1/9999/111111.htm">第一章 章名一</a></td>
              <td class="ccss"><a href="/novel/1/9999/111112.htm">第二章 章名二</a></td></tr>
          <tr><td class="vcss" colspan="2">第二卷 卷名乙</td></tr>
          <tr><td class="ccss"><a href="/novel/1/9999/222221.htm">第一章 章名三</a></td></tr>
        </table>
        </div></body></html>
    """.trimIndent()

    /**
     * Mirrors the live chapter page: navigation lists, bare text runs separated by
     * a single `<br>`, an inline illustration, then the navigation list again.
     */
    private val chapterHtml = """
        <html><body><div id="content">
          <ul id="contentdp"><li><a href="/book/9999.htm">测试小说</a></li><li>第一章 章名一</li></ul>
          正文第一段<br>
          正文第二段<br>
          <img src="/pictures/1/9999/111111/1.jpg" border="0"><br>
          正文第三段<br>
          <ul id="contentdp"><li><a href="/novel/1/9999/111100.htm">上一章</a></li>
          <li><a href="/novel/1/9999/111112.htm">下一章</a></li></ul>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `parses detail metadata`() {
        val detail = Wenku8Parser.parseBookDetail(detailHtml, 9999)
        assertNotNull(detail)
        detail!!
        assertEquals("测试小说", detail.book.title)
        assertEquals("作者甲", detail.book.author)
        assertEquals("测试文库", detail.book.category)
        assertEquals("连载中", detail.book.status)
        assertEquals("2026-01-02", detail.book.updatedAt)
        assertEquals("http://img.wenku8.com/image/1/9999/9999s.jpg", detail.book.coverUrl)
        assertEquals(listOf("标签一", "标签二"), detail.tags)
        assertTrue(detail.intro.contains("简介第一行"))
        assertTrue(detail.intro.contains("简介第二行"))
        assertEquals("第三卷 第二章 章名乙", detail.book.latestChapter)
        assertEquals("/novel/1/9999/", detail.novelPrefix)
    }

    /**
     * On the live catalogue page the table is a direct child of `<body>`, **not**
     * inside `div#content`. A selector requiring that ancestor made every book
     * report zero chapters on a real device even though the page had loaded.
     */
    @Test
    fun `parses a catalogue table that is outside the content div`() {
        val html = """
            <html><head><title>测试小说小说在线阅读与TXT电子书下载-作者甲-测试文库-轻小说文库(www.example.com)</title></head>
            <body>
              <div id="title">测试小说</div>
              <div id="info"></div>
              <table class="css" cellpadding="0" cellspacing="0">
                <tr><td class="vcss" colspan="2">第一卷 卷名甲</td></tr>
                <tr>
                  <td class="ccss"><a href="/novel/3/3988/111111.htm">第一章</a></td>
                  <td class="ccss"><a href="/novel/3/3988/111112.htm">第二章</a></td>
                </tr>
                <tr><td class="vcss" colspan="2">第二卷 卷名乙</td></tr>
                <tr><td class="ccss"><a href="/novel/3/3988/222222.htm">第一章</a></td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val volumes = Wenku8Parser.parseChapterList(html, 3988)
        assertEquals(2, volumes.size)
        assertEquals(listOf(111111, 111112, 222222), volumes.flatMap { it.chapters }.map { it.chapterId })
        assertEquals("第一卷 卷名甲", volumes[0].title)
    }

    @Test
    fun `finds the catalogue table even without the css class`() {
        // Structural fallback: any table that carries chapter cells.
        val html = """
            <html><body>
              <table><tr><td>导航</td></tr></table>
              <table cellpadding="2">
                <tr><td class="vcss">第一卷</td></tr>
                <tr><td class="ccss"><a href="/novel/1/9/123456.htm">第一章</a></td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val volumes = Wenku8Parser.parseChapterList(html, 9)
        assertEquals(1, volumes.size)
        assertEquals(123456, volumes[0].chapters.single().chapterId)
    }

    @Test
    fun `parses volumes and chapters`() {
        val volumes = Wenku8Parser.parseChapterList(tocHtml, 9999)
        assertEquals(2, volumes.size)
        assertEquals("第一卷 卷名甲", volumes[0].title)
        assertEquals(2, volumes[0].chapters.size)
        assertEquals("第二卷 卷名乙", volumes[1].title)
        assertEquals(1, volumes[1].chapters.size)

        val chapters = volumes.flatMap { it.chapters }
        assertEquals(listOf(111111, 111112, 222221), chapters.map { it.chapterId })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals("第一卷 卷名甲", chapters[1].volumeTitle)
    }

    @Test
    fun `parses chapter content with inline illustration and nav`() {
        val content = Wenku8Parser.parseChapterContent(chapterHtml, 9999, 111111)
        assertEquals("第一章 章名一", content.title)
        assertEquals(111100, content.previousChapterId)
        assertEquals(111112, content.nextChapterId)
        assertTrue(content.paragraphs.contains("正文第一段"))
        assertTrue(content.paragraphs.contains("正文第二段"))
        assertTrue(content.paragraphs.contains("正文第三段"))
        // Illustration URLs are resolved to absolute addresses so the image loader
        // can fetch them directly.
        assertEquals(
            listOf("https://www.wenku8.net/pictures/1/9999/111111/1.jpg"),
            content.illustrations,
        )
        // Navigation text must never leak into the body.
        assertTrue(content.paragraphs.none { it.contains("上一章") || it.contains("下一章") })
    }

    @Test
    fun `normalizeBlocks collapses blank separators and keeps image order`() {
        val raw = listOf(
            ContentBlock.Paragraph("第一句"),
            ContentBlock.Paragraph(""),
            ContentBlock.Paragraph("第二句"),
            ContentBlock.Illustration("http://img.wenku8.com/a.jpg"),
            ContentBlock.Paragraph(""),
            ContentBlock.Paragraph(""),
            ContentBlock.Paragraph("第三句"),
        )
        val blocks = Wenku8Parser.normalizeBlocks(raw)
        assertEquals(4, blocks.size)
        assertEquals("第一句", (blocks[0] as ContentBlock.Paragraph).text)
        assertEquals("第二句", (blocks[1] as ContentBlock.Paragraph).text)
        assertTrue(blocks[2] is ContentBlock.Illustration)
        assertEquals("第三句", (blocks[3] as ContentBlock.Paragraph).text)
    }

    @Test
    fun `detects a login wall instead of returning an empty chapter`() {
        val wall = "<html><body><div id=\"content\"><table><tr><td>用户登录</td></tr>" +
            "<tr><td><form action=\"/login.php?do=submit\"></form></td></tr></table></div></body></html>"
        val content = Wenku8Parser.parseChapterContent(wall, 1, 2)
        assertTrue(content.requiresLogin)
        assertTrue(content.blocks.isEmpty() || content.paragraphs.all { it.contains("用户登录") })
    }

    @Test
    fun `parses a book list from ranking markup`() {
        val html = """
            <html><body><div id="content"><ul>
              <li><a href="/book/1234.htm">书甲</a></li>
              <li><a href="/book/5678.htm">书乙</a></li>
              <li><a href="/modules/article/toplist.php?sort=allvisit">更多</a></li>
            </ul></div></body></html>
        """.trimIndent()
        val books = Wenku8Parser.parseBookList(html)
        assertEquals(listOf(1234, 5678), books.map { it.bookId })
        assertEquals("书甲", books.first().title)
    }

    /**
     * Entry markup exactly as the live search and ranking pages emit it: each entry
     * links to its book **twice** — first a cover-only link that carries no text,
     * then the titled link — and the container's text holds `作者:…`.
     *
     * The parser used to take the first `/book/` link, read an empty title and skip
     * the whole entry, so every list silently fell back to [parseBookList]: titles
     * only, no covers, no author.
     */
    private val rankingEntryHtml = """
        <html><body><div id="content"><table class="grid"><tr><td>
          <div style="width:373px;height:136px;float:left;margin:5px 0px 5px 5px;">
            <div style="width:95px;float:left;">
              <a href="/book/3475.htm" title="书甲"><img src="http://img.wenku8.com/image/3/3475/3475s.jpg" height="130" width="90"></a>
            </div>
            <div style="margin-top:2px;">
              <b><a style="font-size:13px;" href="/book/3475.htm" title="书甲">书甲</a></b>
              <p>作者:作者甲/分类:测试文库 更新:2026-01-28/字数:90K/连载中</p>
              <p><a href="/modules/article/addbookcase.php?bid=3475">加入书架</a></p>
            </div>
          </div>
          <div style="width:373px;height:136px;float:left;margin:5px 0px 5px 5px;">
            <div style="width:95px;float:left;">
              <a href="/book/4365.htm" title="书乙"><img src="http://img.wenku8.com/image/4/4365/4365s.jpg" height="130" width="90"></a>
            </div>
            <div style="margin-top:2px;">
              <p><a href="/book/4365.htm">我要阅读</a></p>
              <b><a href="/book/4365.htm" title="书乙">书乙</a></b>
              <p>作者:作者乙/分类:测试文库</p>
              <p><a href="/modules/article/addbookcase.php?bid=4365">加入书架</a></p>
            </div>
          </div>
        </td></tr></table></div></body></html>
    """.trimIndent()

    @Test
    fun `parses entries whose first book link is only the cover`() {
        val books = Wenku8Parser.parseRankingList(rankingEntryHtml)
        assertEquals(listOf(3475, 4365), books.map { it.bookId })

        assertEquals("书甲", books[0].title)
        assertEquals("作者甲", books[0].author)
        assertEquals("http://img.wenku8.com/image/3/3475/3475s.jpg", books[0].coverUrl)

        // The second entry puts its 我要阅读 link *before* the title link: an action
        // label must never be taken for the book's name, and the entry itself must
        // still be found.
        assertEquals("书乙", books[1].title)
        assertEquals("作者乙", books[1].author)
        assertEquals("http://img.wenku8.com/image/4/4365/4365s.jpg", books[1].coverUrl)
    }

    @Test
    fun `prefers the cover linked to the entry's own book`() {
        // A decorative image comes first in the entry, so "the first <img> of the
        // container" would be wrong; the cover is the image the entry links to.
        val html = """
            <html><body><div id="content"><table class="grid"><tr><td>
              <div>
                <img src="http://www.wenku8.net/themes/wenku8/star.gif">
                <a href="/book/3475.htm" title="书甲"><img src="http://img.wenku8.com/image/3/3475/3475s.jpg"></a>
                <b><a href="/book/3475.htm">书甲</a></b>
                <p>作者:作者甲/分类:测试文库</p>
                <p><a href="/modules/article/addbookcase.php?bid=3475">加入书架</a></p>
              </div>
            </td></tr></table></div></body></html>
        """.trimIndent()
        val books = Wenku8Parser.parseRankingList(html)
        assertEquals(1, books.size)
        assertEquals("http://img.wenku8.com/image/3/3475/3475s.jpg", books.first().coverUrl)
    }

    @Test
    fun `search results keep their covers instead of degrading to a plain list`() {
        // Same markup, reached through the search endpoint: the source asks for the
        // ranking list first and only falls back to parseBookList when it is empty.
        val books = Wenku8Parser.parseRankingList(rankingEntryHtml)
        assertTrue("search results must not degrade to the coverless fallback", books.isNotEmpty())
        assertTrue(books.all { it.coverUrl != null })
    }

    @Test
    fun `parses homepage recent updates rows`() {
        val html = """
            <html><body><ul>
              <li>[测试文库] 《<a href="/book/1234.htm">书甲</a>》
                  <a href="/novel/1/1234/999.htm">第某章</a></li>
              <li>作者乙 (01-02)</li>
            </ul></body></html>
        """.trimIndent()
        val books = Wenku8Parser.parseRecentUpdates(html)
        assertEquals(1, books.size)
        assertEquals(1234, books.first().bookId)
        assertEquals("书甲", books.first().title)
        assertEquals("测试文库", books.first().category)
        assertEquals("作者乙", books.first().author)
        assertEquals("01-02", books.first().updatedAt)
        assertEquals("第某章", books.first().latestChapter)
        // The row has no <img>, so the cover comes from the category segment of
        // the latest-chapter link: /novel/1/1234/… -> …/image/1/1234/1234s.jpg.
        assertEquals("http://img.wenku8.com/image/1/1234/1234s.jpg", books.first().coverUrl)
    }

    @Test
    fun `keeps the cover a recent updates row does carry`() {
        // Protocol-relative src, as the source emits elsewhere on the homepage.
        val html = """
            <html><body><ul>
              <li><img src="//img.wenku8.com/image/9/1234/1234s.jpg">
                  [测试文库] 《<a href="/book/1234.htm">书甲</a>》
                  <a href="/novel/1/1234/999.htm">第某章</a></li>
            </ul></body></html>
        """.trimIndent()
        val books = Wenku8Parser.parseRecentUpdates(html)
        assertEquals(1, books.size)
        assertEquals("https://img.wenku8.com/image/9/1234/1234s.jpg", books.first().coverUrl)
    }

    @Test
    fun `leaves the cover empty when the category is unknowable`() {
        // Two anchors but no chapter link: there is no category segment to derive
        // from, and inventing one would request a URL that never exists.
        val html = """
            <html><body><ul>
              <li>[测试文库] 《<a href="/book/1234.htm">书甲</a>》
                  <a href="/modules/article/authorarticle.php?author=作者乙">作者乙</a></li>
            </ul></body></html>
        """.trimIndent()
        val books = Wenku8Parser.parseRecentUpdates(html)
        assertEquals(1, books.size)
        assertEquals(1234, books.first().bookId)
        assertNull(books.first().coverUrl)
    }

    @Test
    fun `reads the category segment out of novel urls`() {
        assertEquals("3", Wenku8Urls.categoryOf("/novel/3/3475/178773.htm"))
        assertEquals("3", Wenku8Urls.categoryOf("https://www.wenku8.net/novel/3/3988/index.htm"))
        assertEquals("1", Wenku8Urls.categoryOf("/novel/1/1973/176596.htm?x=1"))
        assertNull(Wenku8Urls.categoryOf("/book/3475.htm"))
        assertNull(Wenku8Urls.categoryOf(null))
    }

    @Test
    fun `extracts chapter ids and novel prefixes from urls`() {
        assertEquals(111111, Wenku8Parser.chapterIdOf("/novel/1/9999/111111.htm"))
        assertEquals(111111, Wenku8Parser.chapterIdOf("https://www.wenku8.net/novel/1/9999/111111.htm?x=1"))
        // Volume/chapter-list pages are not chapters.
        assertNull(Wenku8Parser.chapterIdOf("/novel/1/9999/index.htm"))
        assertNull(Wenku8Parser.chapterIdOf("/book/9999.htm"))

        assertEquals("/novel/3/3744/", Wenku8Urls.novelPrefixOf("https://www.wenku8.net/novel/3/3744/178715.htm"))
        assertNull(Wenku8Urls.novelPrefixOf("/book/9999.htm"))
        assertEquals(9999, Wenku8Urls.bookIdOf("https://www.wenku8.net/book/9999.htm"))
        assertEquals(9999, Wenku8Urls.normalizeBookInput("https://www.wenku8.net/novel/1/9999/111111.htm"))
        assertEquals(9999, Wenku8Urls.normalizeBookInput("9999"))
    }

    /**
     * The detail page's 阅读 button links to the catalogue page
     * (`/novel/3/3988/index.htm`). Failing to recognise that shape left
     * `novelPrefix` empty, and the UI then reported the book as unreadable even
     * though it has a full chapter list.
     */
    @Test
    fun `recognises the catalogue page as a novel prefix`() {
        assertEquals("/novel/3/3988/", Wenku8Urls.novelPrefixOf("/novel/3/3988/index.htm"))
        assertEquals(
            "/novel/3/3988/",
            Wenku8Urls.novelPrefixOf("https://www.wenku8.net/novel/3/3988/index.htm"),
        )
        // index.htm is a catalogue page, not a chapter.
        assertNull(Wenku8Parser.chapterIdOf("/novel/3/3988/index.htm"))
        assertFalse(Wenku8Urls.isChapterUrl("/novel/3/3988/index.htm"))
        assertTrue(Wenku8Urls.isChapterUrl("/novel/3/3988/178729.htm"))
    }

    @Test
    fun `reads the catalogue link from the reading fieldset`() {
        // Mirrors the real markup of the 阅读 button.
        val html = """
            <html><head><title>测试小说 - 作者甲 - 测试文库 - 轻小说文库</title></head>
            <body><div id="content"><div>
              <table><tr><td><a href="/modules/article/uservote.php?id=3988">测试小说[推一下!]</a></td></tr></table>
              <fieldset><legend>阅读</legend>
                <div style="text-align:center"><a href="/novel/3/3988/index.htm">小说目录</a></div>
              </fieldset>
              <table>
                <tr><td><img src="http://img.wenku8.com/image/3/3988/3988s.jpg" width="168"></td></tr>
                <tr><td>文库分类：测试文库</td><td>小说作者：作者甲</td></tr>
              </table>
            </div></div></body></html>
        """.trimIndent()

        val detail = Wenku8Parser.parseBookDetail(html, 3988)!!
        assertEquals("/novel/3/3988/", detail.novelPrefix)
        assertEquals("3988", detail.novelPrefix.trim('/').split('/').last())
    }

    @Test
    fun `falls back to a cover derived from the catalogue link`() {
        val html = """
            <html><head><title>测试小说 - 作者甲 - 测试文库 - 轻小说文库</title></head>
            <body><div id="content"><div>
              <fieldset><legend>阅读</legend>
                <div><a href="/novel/3/3988/index.htm">小说目录</a></div>
              </fieldset>
            </div></div></body></html>
        """.trimIndent()

        val detail = Wenku8Parser.parseBookDetail(html, 3988)!!
        // No <img> on the page: the cover path is derived from the category.
        // The directory is the book id itself — verified against the live image
        // host (`…/image/3/3/3988s.jpg` answers 404).
        assertEquals("http://img.wenku8.com/image/3/3988/3988s.jpg", detail.book.coverUrl)
        assertEquals("/novel/3/3988/", detail.novelPrefix)
    }

    @Test
    fun `builds urls used by the source`() {
        assertEquals("https://www.wenku8.net/book/1973.htm", Wenku8Urls.book(1973))
        assertEquals(
            "https://www.wenku8.net/novel/1/1973/index.htm",
            Wenku8Urls.chapterList(1973, "/novel/1/1973/"),
        )
        assertEquals(
            "https://www.wenku8.net/novel/1/1973/176596.htm",
            Wenku8Urls.chapter("/novel/1/1973/", 176596),
        )
        assertEquals(
            "https://www.wenku8.net/modules/article/toplist.php?sort=allvisit",
            Wenku8Urls.rank(com.xempastissimo.lightnovelreader.domain.model.RankType.ALL_VISIT),
        )
        assertTrue(Wenku8Urls.rank(com.xempastissimo.lightnovelreader.domain.model.RankType.ALL_VISIT, 2).endsWith("page=2"))
        // The middle segment of a cover path is the book id, not `aid / 1000`.
        assertEquals("http://img.wenku8.com/image/1/1973/1973s.jpg", Wenku8Urls.cover(1973, "1"))
        assertEquals("http://img.wenku8.com/image/3/3475/3475s.jpg", Wenku8Urls.cover(3475, "3"))
        assertEquals(
            "http://img.wenku8.com/image/3/3475/3475s.jpg",
            Wenku8Urls.coverFromNovelUrl(3475, "/novel/3/3475/178773.htm"),
        )
        assertNull(Wenku8Urls.coverFromNovelUrl(3475, "/book/3475.htm"))
    }

    @Test
    fun `parses login captcha detection payload`() {
        val html = "<form action=\"/login.php\" method=\"post\"><input name=\"checkcode\"></form>"
        assertTrue(html.contains("checkcode"))
        assertEquals("https://www.wenku8.net/", Wenku8Urls.BASE + "/")
    }
}
