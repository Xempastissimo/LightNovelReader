package com.xempastissimo.lightnovelreader.data.source.wenku8

import com.xempastissimo.lightnovelreader.core.text.TextCleaner
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The whole-book pack is a *single* text file with no markup, so the only thing that can make
 * a chapter readable offline is guessing its boundaries correctly.
 *
 * Everything asserted here was measured against the live pack first (see README
 * 「整本打包下载」): a banner line, a `<书名>` line, then headings at column zero with bodies
 * indented by four spaces. The two interesting failures are the ones behind the design: the
 * separator inside a chapter title is spelled `•` on the page and `·` in the pack, and
 * illustration chapters are published with an empty body.
 *
 * Fixtures are structural — the same shapes, neutral text — and never contain a book's text.
 */
class Wenku8PackParserTest {

    private val banner = "★☆★☆★☆轻小说文库(Www.WenKu8.Com)☆★☆★☆★"

    /** A pack as the source publishes it: banner, title line, then the body. */
    private fun pack(vararg body: String): String =
        (listOf(banner, "<示例书名>", "") + body).joinToString("\n")

    private fun bytesOf(text: String, charset: Charset = Charsets.UTF_8): ByteArray =
        text.toByteArray(charset)

    private fun sections(bytes: ByteArray, charset: Charset = Charsets.UTF_8) =
        Wenku8PackParser.scan(bytes, charset)

    private fun paragraphs(bytes: ByteArray, section: PackSection, charset: Charset = Charsets.UTF_8) =
        TextCleaner.paragraphs(String(bytes, section.offset, section.length, charset))

    private fun paragraphs(bytes: ByteArray, slice: com.xempastissimo.lightnovelreader.data.source.PackSlice) =
        TextCleaner.paragraphs(String(bytes, slice.offset, slice.length, Charsets.UTF_8))

    /** A catalogue: `卷名 to listOf(章节 id to 章节名)`. */
    private fun catalog(vararg volumes: Pair<String, List<Pair<Int, String>>>): BookDetail {
        var index = 0
        val parsed = volumes.mapIndexed { volumeIndex, (title, chapters) ->
            Volume(
                volumeId = volumeIndex,
                title = title,
                chapters = chapters.map { (chapterId, chapterTitle) ->
                    Chapter(
                        chapterId = chapterId,
                        title = chapterTitle,
                        volumeId = volumeIndex,
                        volumeTitle = title,
                        index = index++,
                    )
                },
            )
        }
        return BookDetail(book = Book(bookId = 1, title = "示例书名"), volumes = parsed)
    }

    // ------------------------------------------------------------------------ scanning

    /**
     * A heading starts at column zero; the blank lines and the indented lines under it are its
     * body. The range has to address the *bytes*, because that is what the reader seeks to
     * after a restart.
     */
    @Test
    fun `a section spans from its heading to the next one`() {
        val bytes = bytesOf(
            pack(
                "第一卷 第一章 开端",
                "",
                "    第一段。",
                "",
                "    第二段。",
                "",
                "第一卷 第二章 转折",
                "",
                "    转折的正文。",
                "",
            ),
        )

        val sections = sections(bytes)

        assertEquals(
            listOf("第一卷 第一章 开端", "第一卷 第二章 转折"),
            sections.map { it.heading },
        )
        assertEquals(
            listOf(listOf("第一段。", "第二段。"), listOf("转折的正文。")),
            sections.map { paragraphs(bytes, it) },
        )
    }

    /** The banner and the `<书名>` line are the pack's own header, not chapters. */
    @Test
    fun `the packs own header lines are not sections`() {
        val sections = sections(bytesOf(pack("第一卷 第一章 开端", "", "    正文。", "")))

        assertEquals(listOf("第一卷 第一章 开端"), sections.map { it.heading })
    }

    /** The site publishes the pack as UTF-8 or GBK; both have to slice at byte boundaries. */
    @Test
    fun `a GBK pack decodes to the same headings and bodies`() {
        val gbk = Charset.forName("GBK")
        val bytes = bytesOf(pack("第一卷 第一章 开端", "", "    第一段。", "", "    第二段。", ""), gbk)

        val section = sections(bytes, gbk).single()

        assertEquals("第一卷 第一章 开端", section.heading)
        assertEquals(listOf("第一段。", "第二段。"), paragraphs(bytes, section, gbk))
    }

    /** A full-width indent and CRLF endings are what a copy edited on Windows can look like. */
    @Test
    fun `CRLF and an ideographic indent still delimit a body`() {
        val text = pack("第一卷 第一章 开端", "", "\u3000\u3000第一段。", "", "第一卷 第二章 转折", "", "\u3000\u3000第二段。", "")
            .replace("\n", "\r\n")
        val bytes = bytesOf(text)
        val gbk = Charset.forName("GBK")

        val sections = sections(bytes, Charsets.UTF_8)
        assertEquals(listOf("第一卷 第一章 开端", "第一卷 第二章 转折"), sections.map { it.heading })
        assertEquals(listOf(listOf("第一段。"), listOf("第二段。")), sections.map { paragraphs(bytes, it) })

        // The same shape in GBK, where U+3000 is two bytes rather than three.
        val gbkBytes = bytesOf(text, gbk)
        val gbkSections = sections(gbkBytes, gbk)
        assertEquals(listOf("第一卷 第一章 开端", "第一卷 第二章 转折"), gbkSections.map { it.heading })
        assertEquals(
            listOf(listOf("第一段。"), listOf("第二段。")),
            gbkSections.map { paragraphs(gbkBytes, it, gbk) },
        )
    }

    /** A section's range never overlaps its neighbour: a slice must not carry another chapter. */
    @Test
    fun `sections do not overlap and stay inside the file`() {
        val bytes = bytesOf(pack("第一卷 第一章 开端", "", "    甲。", "", "第一卷 第二章 转折", "", "    乙。", ""))

        val sections = sections(bytes)

        sections.zipWithNext().forEach { (first, second) ->
            assertTrue(first.offset + first.length <= second.offset)
        }
        assertTrue(sections.all { it.offset >= 0 && it.offset + it.length <= bytes.size })
    }

    /** The body of a section is exactly what sits under its heading. */
    @Test
    fun `a section starts right after its own heading`() {
        val bytes = bytesOf(pack("第一卷 第一章 开端", "", "    甲。", "", "第一卷 第二章 转折", "", "    乙。", ""))

        val sections = sections(bytes)

        assertEquals(
            listOf(listOf("甲。"), listOf("乙。")),
            sections.map { paragraphs(bytes, it) },
        )
        // Nothing between one range's end and the next heading is a chapter's text.
        sections.forEach { section ->
            assertTrue(String(bytes, section.offset, section.length, Charsets.UTF_8).startsWith("\n"))
        }
    }

    // ------------------------------------------------------------------------ matching

    @Test
    fun `a heading is the volume title joined to the chapter title`() {
        val detail = catalog("第一卷" to listOf(1 to "第一章 开端", 2 to "第二章 转折"))
        val bytes = bytesOf(
            pack(
                "第一卷 第一章 开端", "", "    甲。", "",
                "第一卷 第二章 转折", "", "    乙。", "",
            ),
        )

        val match = Wenku8PackParser.match(detail, sections(bytes))

        assertEquals(setOf(1, 2), match.slices.keys)
        assertEquals(2, match.coveredChapters)
        assertEquals(0, match.unmatchedHeadings)
        assertEquals(listOf("甲。"), paragraphs(bytes, match.slices.getValue(1)))
        assertEquals(listOf("乙。"), paragraphs(bytes, match.slices.getValue(2)))
    }

    /**
     * The page and the pack disagree about the separator inside a chapter title — `•` versus
     * `·`. Matching verbatim loses every chapter that contains one (about 3% of a real book),
     * so the comparison normalises it.
     */
    @Test
    fun `a bullet written one way on the page and another in the pack still matches`() {
        val detail = catalog("短篇集 Alter.1" to listOf(11 to "你的气息•辛的状况"))
        val bytes = bytesOf(pack("短篇集 Alter.1 你的气息·辛的状况", "", "    正文。", ""))

        val match = Wenku8PackParser.match(detail, sections(bytes))

        assertEquals(setOf(11), match.slices.keys)
        assertEquals(0, match.unmatchedHeadings)
    }

    /** A catalogue whose volume cell was missing yields the chapter title alone. */
    @Test
    fun `a chapter without a volume prefix matches on its own title`() {
        val detail = catalog("正文" to listOf(21 to "后记"))
        val bytes = bytesOf(pack("后记", "", "    正文。", ""))

        val match = Wenku8PackParser.match(detail, sections(bytes))

        assertEquals(setOf(21), match.slices.keys)
    }

    /**
     * Two chapters may share a title. Sections are consumed in file order, so the second one
     * gets the second body rather than both claiming the first.
     */
    @Test
    fun `chapters that share a title take the sections in order`() {
        val detail = catalog("第一卷" to listOf(31 to "后记", 32 to "后记"))
        val bytes = bytesOf(
            pack(
                "第一卷 后记", "", "    甲。", "",
                "第一卷 后记", "", "    乙。", "",
            ),
        )

        val match = Wenku8PackParser.match(detail, sections(bytes))

        assertEquals(listOf("甲。"), paragraphs(bytes, match.slices.getValue(31)))
        assertEquals(listOf("乙。"), paragraphs(bytes, match.slices.getValue(32)))
    }

    /**
     * Illustration chapters are published as a heading with an empty body — the pack holds no
     * images. Recording them would make the reader serve a blank page instead of the online
     * chapter, which does have the artwork, so they are reported as not covered.
     */
    @Test
    fun `an illustration chapter is present but not covered`() {
        val detail = catalog("第一卷" to listOf(41 to "插图", 42 to "后记"))
        val bytes = bytesOf(
            pack(
                "第一卷 插图", "", "", "",
                "第一卷 后记", "", "    正文。", "",
            ),
        )
        val scanned = sections(bytes)

        // The heading is there and its range is a few line breaks — which is not text.
        assertEquals(listOf("第一卷 插图", "第一卷 后记"), scanned.map { it.heading })
        assertTrue(scanned.first().length > 0)
        assertTrue(!scanned.first().hasText)

        val match = Wenku8PackParser.match(detail, scanned)

        assertEquals(setOf(42), match.slices.keys)
        assertEquals(1, match.coveredChapters)
        assertEquals(0, match.unmatchedHeadings)
        assertNull(match.slices[41])
    }

    /**
     * The pack is a snapshot the site refreshes on its own schedule, so it can lag the
     * catalogue. The chapters it does not hold are simply not covered — they stay readable
     * online — and the count is what the UI reports as "262/270".
     */
    @Test
    fun `chapters the pack does not hold are left out`() {
        val detail = catalog("第一卷" to listOf(51 to "第一章", 52 to "第二章", 53 to "第三章"))
        val bytes = bytesOf(
            pack(
                "第一卷 第一章", "", "    甲。", "",
                "第一卷 第二章", "", "    乙。", "",
            ),
        )

        val match = Wenku8PackParser.match(detail, sections(bytes))

        assertEquals(setOf(51, 52), match.slices.keys)
        assertEquals(2, match.coveredChapters)
        assertEquals(0, match.unmatchedHeadings)
    }

    /** A heading no catalogue chapter claims is counted, not matched to the wrong chapter. */
    @Test
    fun `a heading the catalogue does not have is unmatched`() {
        val detail = catalog("第一卷" to listOf(61 to "第一章"))
        val bytes = bytesOf(
            pack(
                "第一卷 第一章", "", "    甲。", "",
                "第一卷 番外篇", "", "    乙。", "",
            ),
        )

        val match = Wenku8PackParser.match(detail, sections(bytes))

        assertEquals(setOf(61), match.slices.keys)
        assertEquals(1, match.unmatchedHeadings)
    }

    /** A catalogue with no chapters cannot be matched, and must not throw. */
    @Test
    fun `an empty catalogue matches nothing`() {
        val match = Wenku8PackParser.match(BookDetail(book = Book(bookId = 1, title = "x")), sections(bytesOf(pack())))

        assertTrue(match.slices.isEmpty())
        assertEquals(0, match.coveredChapters)
    }

    // --------------------------------------------------------------------- normalisation

    @Test
    fun `normalising a heading unifies bullets, spaces and zero width characters`() {
        assertEquals("你的气息·辛的状况", Wenku8PackParser.normalizeHeading("你的气息•辛的状况"))
        assertEquals("你的气息·辛的状况", Wenku8PackParser.normalizeHeading("你的气息 ・ 辛的状况"))
        assertEquals("第一卷 后记", Wenku8PackParser.normalizeHeading("第一卷\u3000\u00a0后记"))
        assertEquals("序章 a", Wenku8PackParser.normalizeHeading("\u200b序章\u200b    a\ufeff"))
        assertEquals("第一卷 终章. 完", Wenku8PackParser.normalizeHeading("第一卷 终章． 完"))
    }
}
