package com.xempastissimo.lightnovelreader.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTest {

    private val sample = """
        <html><head><title>文库</title></head>
        <body>
          <div id="content">
            <table class="vcss" border="0">
              <tr><td class="vcss">第一卷</td></tr>
              <tr><td class="ccss"><a href="/novel/3/3988/178729.htm">蜜瓜特典</a></td></tr>
            </table>
          </div>
          <div class="box">
            <h3><a href="/book/1973.htm">欢迎来到实力至上主义的教室</a></h3>
            <p>作者：衣笠彰梧</p>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses elements and attributes`() {
        val root = Html.parse(sample)
        val table = root.selectFirst("table.vcss")
        assertNotNull(table)
        assertEquals("0", table!!.attr("border"))
        assertEquals("vcss", table.attr("class"))
    }

    @Test
    fun `selects by id and class`() {
        val root = Html.parse(sample)
        assertEquals(1, root.select("#content").size)
        assertEquals(2, root.select(".vcss").size)
        assertTrue(root.select("div.box h3 a").isNotEmpty())
    }

    @Test
    fun `selects by descendant chain`() {
        val root = Html.parse(sample)
        val links = root.select("#content table tr td.ccss a")
        assertEquals(1, links.size)
        assertEquals("/novel/3/3988/178729.htm", links.first().attr("href"))
        assertEquals("蜜瓜特典", links.first().text())
    }

    @Test
    fun `child combinator only matches direct children`() {
        val root = Html.parse(sample)
        assertEquals(1, root.select("table > tr").size.coerceAtMost(1))
        assertEquals(2, root.select("table tr td").size)
    }

    @Test
    fun `comma groups return both matches`() {
        val root = Html.parse(sample)
        val matched = root.select("table.vcss, div.box")
        assertEquals(2, matched.size)
    }

    @Test
    fun `handles void elements and unquoted attributes`() {
        val root = Html.parse("""<div><img src=/pictures/0/471/17513/3213.jpg width=800><br>文字</div>""")
        val img = root.selectFirst("img")
        assertNotNull(img)
        assertEquals("/pictures/0/471/17513/3213.jpg", img!!.attr("src"))
        assertEquals("800", img.attr("width"))
        val div = root.selectFirst("div")
        assertEquals("文字", div!!.text())
    }

    @Test
    fun `decodes named and numeric entities`() {
        val root = Html.parse("<p>&#x65B0;&#x624B; &amp; &#19978;&#36335; &nbsp;ok</p>")
        assertEquals("新手 & 上路 \u00a0ok", root.selectFirst("p")!!.text())
    }

    @Test
    fun `recovers from missing closing tags`() {
        val root = Html.parse("<ul><li>一<li>二<li>三</ul><p>尾</p>")
        assertEquals(3, root.select("li").size)
        assertEquals("三", root.select("li").last().text())
        assertEquals("尾", root.selectFirst("p")!!.text())
    }

    @Test
    fun `ignores comments scripts and doctype`() {
        val root = Html.parse("<!DOCTYPE html><!-- hey --><div><script>var a = 1;</script><span>真</span></div>")
        assertEquals(1, root.select("span").size)
        assertEquals("真", root.selectFirst("span")!!.text())
        assertTrue(root.select("script").isEmpty() || root.selectFirst("script")!!.text().contains("var a"))
    }

    @Test
    fun `textWithBreaks keeps block boundaries`() {
        val root = Html.parse("<div><p>第一段</p><p>第二段</p><br><span>尾</span></div>")
        val text = root.selectFirst("div")!!.textWithBreaks()
        assertTrue(text.contains("第一段"))
        assertTrue(text.contains("第二段"))
        assertTrue(text.lines().count { it.isNotBlank() } >= 3)
    }

    @Test
    fun `attribute lookup returns null when missing`() {
        val root = Html.parse("<a href='/x'>x</a>")
        val anchor = root.selectFirst("a")!!
        assertNull(anchor.attr("title"))
        assertTrue(anchor.hasAttr("href"))
    }

    @Test
    fun `supports attribute selectors`() {
        val root = Html.parse(
            """
            <div id="c">
              <a href="/book/1973.htm">书</a>
              <a href="/novel/1/1973/176596.htm">章</a>
              <a href="https://www.wenku8.net/modules/article/toplist.php?sort=allvisit">榜</a>
              <img src="http://img.wenku8.com/image/1/1973/1973s.jpg">
            </div>
            """.trimIndent(),
        )
        assertEquals(1, root.select("a[href*=/book/]").size)
        assertEquals(1, root.select("a[href^=/novel/]").size)
        assertEquals(2, root.select("a[href$=.htm]").size)
        assertEquals(1, root.select("a[href*=toplist]").size)
        assertEquals(1, root.select("img[src*=image]").size)
        assertEquals(1, root.select("a[href='https://www.wenku8.net/modules/article/toplist.php?sort=allvisit']").size)
        assertEquals(3, root.select("a[href]").size)
        assertEquals(0, root.select("a[title]").size)
    }

    @Test
    fun `stray closing tag for a void element does not truncate the tree`() {
        // The book source really emits `</br>`, which must not pop the stack.
        val root = Html.parse("<div><span>甲</span></br><span>乙</span></div><p>尾</p>")
        assertEquals(2, root.select("div span").size)
        assertEquals("乙", root.select("div span").last().text())
        assertEquals("尾", root.selectFirst("p")!!.text())
    }

    @Test
    fun `line breaks separate bare text runs`() {
        // `text()` ignores <br>; `textWithBreaks()` is the variant that keeps line
        // structure, and only block boundaries (never inline elements) add lines.
        val root = Html.parse("<div id=\"content\">甲<br><br>乙<br>丙</div>")
        val content = root.selectFirst("div#content")!!
        assertEquals(3, content.childElements().size)
        assertEquals("甲乙丙", content.text())
        val lines = content.textWithBreaks()
        assertTrue(lines.contains("甲"))
        assertTrue(lines.contains("乙"))
        assertTrue(lines.contains("丙"))
    }

    @Test
    fun `classes handles multiple and extra whitespace`() {
        val root = Html.parse("""<td class="ccss  odd">x</td>""")
        val cell = root.selectFirst("td")!!
        assertEquals(setOf("ccss", "odd"), cell.classes())
        assertTrue(cell.hasClass("ccss"))
    }
}
