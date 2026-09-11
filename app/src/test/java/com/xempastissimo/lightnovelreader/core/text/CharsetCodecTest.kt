package com.xempastissimo.lightnovelreader.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class CharsetCodecTest {

    private val gbk = Charset.forName("GBK")

    @Test
    fun `decodes gbk payload when title declares utf-8`() {
        // A real-world trap of the source: header says UTF-8, bytes are GBK.
        val payload = "<title>轻小说文库</title>".toByteArray(gbk)
        val decoded = CharsetCodec.decode(payload, StandardCharsets.UTF_8)
        assertEquals("<title>轻小说文库</title>", decoded)
    }

    @Test
    fun `honours a correct declared charset`() {
        val payload = "巻".toByteArray(gbk)
        assertEquals("巻", CharsetCodec.decode(payload, gbk))
    }

    @Test
    fun `parses charset from content type header`() {
        assertEquals(gbk, CharsetCodec.charsetFromContentType("text/html; charset=gb2312"))
        assertEquals(StandardCharsets.UTF_8, CharsetCodec.charsetFromContentType("text/html; charset=UTF-8"))
        assertEquals(null, CharsetCodec.charsetFromContentType("text/html"))
    }

    @Test
    fun `parses meta charset from html body`() {
        val html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gbk\"></head>"
        assertEquals(gbk, CharsetCodec.charsetFromHtml(html.toByteArray(gbk)))
    }

    @Test
    fun `gbk without any declaration still decodes`() {
        val payload = "<a href=\"/book/1973.htm\">欢迎来到实力至上主义的教室</a>".toByteArray(gbk)
        val decoded = CharsetCodec.decode(payload, null)
        assertTrue(decoded.contains("欢迎来到实力至上主义的教室"))
    }

    @Test
    fun `empty payload decodes to empty string`() {
        assertEquals("", CharsetCodec.decode(ByteArray(0), gbk))
    }
}

class TextCleanerTest {

    @Test
    fun `single line collapses whitespace`() {
        assertEquals("轻小说 文库", TextCleaner.oneLine("  轻小说 \t\n 文库  "))
        assertEquals("", TextCleaner.oneLine(null))
    }

    @Test
    fun `paragraphs splits on newlines and drops blanks`() {
        val raw = "第一段\n\n\n第二段\n   \n第三段"
        assertEquals(listOf("第一段", "第二段", "第三段"), TextCleaner.paragraphs(raw))
    }

    @Test
    fun `paragraphs normalises exotic spaces and zero width chars`() {
        val raw = "\u3000序章\u00a0序\u200b章\r\n后记"
        assertEquals(listOf("序章 序章", "后记"), TextCleaner.paragraphs(raw))
    }

    @Test
    fun `body joins paragraphs with newline`() {
        assertEquals("甲\n乙", TextCleaner.body("甲\r\n\r\n\n乙"))
    }

    @Test
    fun `paragraphs on blank input is empty`() {
        assertTrue(TextCleaner.paragraphs("   \n  \n").isEmpty())
        assertTrue(TextCleaner.paragraphs(null).isEmpty())
    }
}
