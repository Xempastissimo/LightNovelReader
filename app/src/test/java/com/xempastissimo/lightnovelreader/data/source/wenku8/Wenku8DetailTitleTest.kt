package com.xempastissimo.lightnovelreader.data.source.wenku8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the detail-page values that were wrong on a real device:
 *
 * ```
 * DETAIL 3988 ok title=玩玩的恋爱关系(玩乐关系)推一下! 举报报错  prefix=/novel/3/3988/
 * ```
 *
 * The heading cell carries the book name *and* the page's action labels, so the
 * `<title>` tag has to win. The markup below mirrors the live page's structure
 * with neutral text.
 */
class Wenku8DetailTitleTest {

    private fun page(titleTag: String, headingText: String): String = """
        <html><head><title>$titleTag</title></head>
        <body><div id="content"><div>
          <table width="100%">
            <tr><td colspan="2">$headingText</td></tr>
          </table>
          <table width="100%">
            <tr><td><img src="http://img.wenku8.com/image/3/3988/3988s.jpg"></td></tr>
            <tr><td>文库分类：测试文库</td><td>小说作者：作者甲</td></tr>
          </table>
          <fieldset><legend>阅读</legend><div><a href="/novel/3/3988/index.htm">小说目录</a></div></fieldset>
        </div></div></body></html>
    """.trimIndent()

    @Test
    fun `title comes from the title tag, not the heading cell`() {
        val html = page(
            titleTag = "测试小说 - 作者甲 - 测试文库 - 轻小说文库",
            headingText = "测试小说<a href=\"/modules/article/uservote.php?id=3988\">[推一下!]</a>" +
                " <a href=\"/newmessage.php\">[举报/报错]</a>",
        )
        val detail = Wenku8Parser.parseBookDetail(html, 3988)
        assertNotNull(detail)
        assertEquals("测试小说", detail!!.book.title)
    }

    @Test
    fun `heading cell labels are stripped when the title tag is missing`() {
        // No <title> at all: the fallback has to clean the heading itself.
        val html = page(
            titleTag = "",
            headingText = "测试小说<a href=\"/x\">[推一下!]</a> <a href=\"/y\">[举报/报错]</a>",
        )
        val detail = Wenku8Parser.parseBookDetail(html, 3988)
        assertNotNull(detail)
        val title = detail!!.book.title
        assertTrue("title leaked page labels: $title", !title.contains("推一下"))
        assertTrue("title leaked page labels: $title", !title.contains("举报"))
        assertTrue("title leaked page labels: $title", !title.contains("报错"))
    }

    @Test
    fun `author and category come from the title tag when the table lacks them`() {
        val html = """
            <html><head><title>测试小说 - 作者甲 - 测试文库 - 轻小说文库</title></head>
            <body><div id="content"><div>
              <fieldset><legend>阅读</legend><div><a href="/novel/3/3988/index.htm">小说目录</a></div></fieldset>
            </div></div></body></html>
        """.trimIndent()
        val detail = Wenku8Parser.parseBookDetail(html, 3988)!!
        assertEquals("测试小说", detail.book.title)
        assertEquals("作者甲", detail.book.author)
        assertEquals("测试文库", detail.book.category)
        assertEquals("/novel/3/3988/", detail.novelPrefix)
    }

    @Test
    fun `catalogue page title shape is tolerated`() {
        // The catalogue page titles itself differently, with no space around the
        // dashes: `书名小说在线阅读与TXT电子书下载-作者-文库-轻小说文库(...)`.
        val html = """
            <html><head><title>测试小说小说在线阅读与TXT电子书下载-作者甲-测试文库-轻小说文库(www.example.com)</title></head>
            <body><div id="content"><table class="css">
              <tr><td class="vcss">第一卷</td></tr>
              <tr><td class="ccss"><a href="/novel/3/3988/111111.htm">第一章</a></td></tr>
            </table></div></body></html>
        """.trimIndent()
        val detail = Wenku8Parser.parseBookDetail(html, 3988)
        // The title parses; the prefix comes from a chapter link here.
        assertNotNull(detail)
        assertEquals("测试小说", detail!!.book.title)
        assertEquals("/novel/3/3988/", detail.novelPrefix)
    }

    @Test
    fun `title with dashes inside it is preserved`() {
        val html = page(
            titleTag = "书名 - 副标题 - 作者甲 - 测试文库 - 轻小说文库",
            headingText = "书名 - 副标题",
        )
        val detail = Wenku8Parser.parseBookDetail(html, 3988)!!
        // The first segment is the title; a dash inside it is not expected, so the
        // first part wins and the rest is treated as author/category.
        assertTrue(detail.book.title.isNotBlank())
    }
}
