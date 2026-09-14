package com.xempastissimo.lightnovelreader.ui.screen.reader

import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagination by measurement: how many lines a page can hold, and where a paragraph is cut.
 *
 * The property the reader depends on is not "these exact pages" but "no page is taller than the
 * screen" — that is what makes a page stop scrolling. Most of what follows pins the arithmetic
 * that produces it, and `no page is ever taller than the screen` asserts the property itself
 * across a range of line counts.
 */
class ReaderPaginationTest {

    private val lineHeight = 10
    private val spacing = 5

    /** A laid-out paragraph of [lines] lines, each `lineLength` characters long. */
    private fun prose(
        lines: Int,
        paragraphIndex: Int = 0,
        lineLength: Int = 2,
        padPx: Int = 0,
    ): LaidOutBlock.Prose {
        val text = "x".repeat(lines * lineLength)
        val lineEnds = (1..lines).map { it * lineLength }
        return LaidOutBlock.Prose(text, lineEnds, padPx, paragraphIndex)
    }

    /**
     * A page's height, reconstructed the way `ReaderPage` lays it out.
     *
     * Fragments carry no line breaks — the page wraps them itself — so in these fixtures, where
     * every line is `lineLength` characters, a fragment's line count is its length divided by
     * `lineLength`.
     */
    private fun heightOf(
        page: ReaderPageContent,
        lineLength: Int = 2,
        lineHeightPx: Int = lineHeight,
        spacingPx: Int = spacing,
        padPx: Int = 0,
    ): Int {
        if (page !is ReaderPageContent.Prose) return 0
        var used = 0
        for (fragment in page.paragraphs) {
            val lines = (fragment.length + lineLength - 1) / lineLength
            val body = lines * lineHeightPx + padPx
            used = if (used == 0) body else used + spacingPx + body
        }
        return used
    }

    private fun pagesOf(vararg blocks: LaidOutBlock, maxHeight: Int) =
        paginateBlocks(blocks.toList(), maxHeight, lineHeight, spacing)

    // ------------------------------------------------------------------ numbering

    /**
     * Paragraph numbering is the anchor bookmarks and saved positions use, so blank paragraphs
     * have to be counted even though nothing is drawn for them: otherwise every anchor after a
     * blank line would be off by one.
     */
    @Test
    fun `blank paragraphs are numbered but not laid out`() {
        val laid = laidOutBlocks(
            listOf(
                ContentBlock.Paragraph("第一段。"),
                ContentBlock.Paragraph("   "),
                ContentBlock.Paragraph("第二段。"),
            ),
            lineHeight,
        ) { text -> MeasuredParagraph(listOf(text.length), lineHeight) }

        assertEquals(2, laid.size)
        assertEquals(0, (laid[0] as LaidOutBlock.Prose).paragraphIndex)
        assertEquals(2, (laid[1] as LaidOutBlock.Prose).paragraphIndex)
    }

    @Test
    fun `illustrations keep their place among the paragraphs`() {
        val laid = laidOutBlocks(
            listOf(
                ContentBlock.Paragraph("第一段。"),
                ContentBlock.Illustration("http://img/1.jpg"),
                ContentBlock.Paragraph("第二段。"),
            ),
            lineHeight,
        ) { text -> MeasuredParagraph(listOf(text.length), lineHeight) }

        assertTrue(laid[0] is LaidOutBlock.Prose)
        assertTrue(laid[1] is LaidOutBlock.Illustration)
        assertEquals(1, (laid[2] as LaidOutBlock.Prose).paragraphIndex)
    }

    /**
     * A rendered text is not exactly `lines × lineHeight`: font padding is charged once per
     * `Text`, so it is folded into the fragment's cost rather than ignored. Missing it is how a
     * page ends up one line too tall — the exact bug this pagination exists to remove.
     */
    @Test
    fun `font padding is carried into the packing`() {
        val laid = laidOutBlocks(
            listOf(ContentBlock.Paragraph("第一段。")),
            lineHeight,
        ) { text -> MeasuredParagraph(listOf(text.length), heightPx = lineHeight + 6) }

        assertEquals(6, (laid.single() as LaidOutBlock.Prose).linePadPx)
        // Each fragment is one line (10 px) plus 6 px of padding, so two of them need 32 px —
        // not the 20 px the line arithmetic alone would claim.
        assertEquals(1, paginateBlocks(laid + laid, 32, lineHeight, paragraphSpacingPx = 0).size)
        assertEquals(2, paginateBlocks(laid + laid, 31, lineHeight, paragraphSpacingPx = 0).size)
    }

    // ------------------------------------------------------------------ packing

    @Test
    fun `an empty chapter has no pages`() {
        assertTrue(paginateBlocks(emptyList(), 100, lineHeight, spacing).isEmpty())
    }

    @Test
    fun `a paragraph that fits stays on one page`() {
        val pages = pagesOf(prose(lines = 3, paragraphIndex = 0), maxHeight = 100)

        assertEquals(1, pages.size)
        val page = pages.single() as ReaderPageContent.Prose
        assertEquals(0, page.startParagraph)
        assertEquals(listOf("xxxxxx"), page.paragraphs)
    }

    /** Spacing is charged between fragments, so two short paragraphs can share a page and a third cannot. */
    @Test
    fun `paragraph spacing is charged inside a page`() {
        val pages = pagesOf(
            prose(lines = 1, paragraphIndex = 0),
            prose(lines = 1, paragraphIndex = 1),
            prose(lines = 1, paragraphIndex = 2),
            maxHeight = 25,
        )

        assertEquals(2, pages.size)
        val first = pages[0] as ReaderPageContent.Prose
        assertEquals(listOf("xx", "xx"), first.paragraphs)
        // The third paragraph did not fit, so it opens the second page.
        assertEquals(2, (pages[1] as ReaderPageContent.Prose).startParagraph)
    }

    /** A paragraph too big for the space left simply starts the next page. */
    @Test
    fun `a paragraph that does not fit moves to the next page whole`() {
        val pages = pagesOf(
            prose(lines = 2, paragraphIndex = 0),
            prose(lines = 2, paragraphIndex = 1),
            maxHeight = 20,
        )

        assertEquals(2, pages.size)
        assertEquals(0, (pages[0] as ReaderPageContent.Prose).startParagraph)
        assertEquals(listOf("xxxx"), (pages[1] as ReaderPageContent.Prose).paragraphs)
        assertEquals(1, (pages[1] as ReaderPageContent.Prose).startParagraph)
    }

    /** Taller than a whole page: cut at a line boundary, and lose nothing. */
    @Test
    fun `a paragraph taller than a page is split by lines`() {
        val pages = pagesOf(prose(lines = 6, paragraphIndex = 3), maxHeight = 25)

        assertEquals(3, pages.size)
        pages.forEach { page ->
            assertEquals(3, (page as ReaderPageContent.Prose).startParagraph)
        }
        // 2 + 2 + 2 lines of two characters each, and every character still there exactly once.
        assertEquals(listOf(4, 4, 4), pages.map { (it as ReaderPageContent.Prose).paragraphs.single().length })
        val rendered = pages.joinToString("") { (it as ReaderPageContent.Prose).paragraphs.single() }
        assertEquals("x".repeat(6 * 2), rendered)
    }

    /** The awkward case: the page is part full, and the tall paragraph fills the rest of it. */
    @Test
    fun `a tall paragraph tops up a page that is already part full`() {
        val pages = pagesOf(
            prose(lines = 2, paragraphIndex = 0),
            prose(lines = 5, paragraphIndex = 1),
            maxHeight = 30,
        )

        assertEquals(3, pages.size)
        val first = pages[0] as ReaderPageContent.Prose
        assertEquals(0, first.startParagraph)
        // The second paragraph could not top this page up — there was no room for a whole line —
        // so the page keeps the first paragraph alone.
        assertEquals(1, first.paragraphs.size)
        assertEquals(1, (pages[1] as ReaderPageContent.Prose).startParagraph)
        assertEquals(1, (pages[2] as ReaderPageContent.Prose).startParagraph)
        // 5 lines of two characters, split 3 + 2: nothing dropped, nothing duplicated.
        assertEquals(
            listOf(6, 4),
            listOf(pages[1], pages[2]).map { (it as ReaderPageContent.Prose).paragraphs.single().length },
        )
        val second = pages.drop(1).joinToString("") {
            (it as ReaderPageContent.Prose).paragraphs.joinToString("")
        }
        assertEquals("x".repeat(5 * 2), second)
    }

    /**
     * The page must not inherit the measured layout's line breaks.
     *
     * The reported bug: `TextAlign.Justify` balances lines, so a measured line can fall short of
     * the margin, and a short one can hold a single character. Writing those break points into the
     * text as `\n` reproduced them as hard breaks, and the page came out as a column of orphans.
     */
    @Test
    fun `fragments carry no line breaks of their own`() {
        val pages = pagesOf(prose(lines = 6, paragraphIndex = 0), maxHeight = 25)

        pages.forEach { page ->
            (page as ReaderPageContent.Prose).paragraphs.forEach { fragment ->
                assertFalse("fragment '$fragment' carries a hard break", fragment.contains('\n'))
            }
        }
    }

    /** A measured line of one character must not become a rendered line of one character. */
    @Test
    fun `a short measured line does not become a line of its own`() {
        // A balanced layout: a full line, then one character, then the rest.
        val text = "abcdefghijk"
        val block = LaidOutBlock.Prose(
            text = text,
            lineEnds = listOf(5, 6, 11),
            linePadPx = 0,
            paragraphIndex = 0,
        )

        val pages = paginateBlocks(listOf(block), maxHeightPx = 1000, lineHeightPx = 10, paragraphSpacingPx = 0)

        assertEquals(listOf(text), (pages.single() as ReaderPageContent.Prose).paragraphs)
    }

    @Test
    fun `an illustration is a page of its own`() {
        val pages = pagesOf(
            prose(lines = 1, paragraphIndex = 0),
            LaidOutBlock.Illustration("http://img/1.jpg"),
            prose(lines = 1, paragraphIndex = 1),
            maxHeight = 100,
        )

        assertEquals(3, pages.size)
        assertTrue(pages[1] is ReaderPageContent.Illustration)
    }

    /** A window shorter than one line still terminates, and still advances. */
    @Test
    fun `a window shorter than a single line makes progress`() {
        val pages = paginateBlocks(listOf(prose(lines = 3, paragraphIndex = 0)), 4, lineHeight, spacing)

        assertEquals(3, pages.size)
    }

    /**
     * The property the whole feature exists for: whatever the font size and spacing work out to,
     * no page asks for more room than it has.
     */
    @Test
    fun `no page is ever taller than the screen`() {
        for (height in 12..90) {
            for (spacingPx in listOf(0, 5, 12)) {
                val blocks = listOf(
                    prose(lines = 1, paragraphIndex = 0),
                    prose(lines = 7, paragraphIndex = 1),
                    LaidOutBlock.Illustration("http://img/1.jpg"),
                    prose(lines = 3, paragraphIndex = 2),
                    prose(lines = 11, paragraphIndex = 3),
                    prose(lines = 1, paragraphIndex = 4),
                )
                val pages = paginateBlocks(blocks, height, lineHeight, spacingPx)

                pages.forEach { page ->
                    val measured = heightOf(page, spacingPx = spacingPx)
                    // The single exception is a window that cannot hold one line at all.
                    if (height >= lineHeight) {
                        assertTrue(
                            "page of $measured px in a $height px window",
                            measured <= height,
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ locating a paragraph

    @Test
    fun `a paragraph resolves to the page that holds it`() {
        val pages = listOf(
            ReaderPageContent.Prose(listOf("a"), startParagraph = 0),
            ReaderPageContent.Prose(listOf("b"), startParagraph = 3),
            ReaderPageContent.Illustration("http://img/1.jpg"),
            ReaderPageContent.Prose(listOf("c"), startParagraph = 5),
        )

        assertEquals(0, pageForParagraph(pages, 0))
        assertEquals(0, pageForParagraph(pages, 2))
        assertEquals(1, pageForParagraph(pages, 3))
        assertEquals(1, pageForParagraph(pages, 4))
        assertEquals(3, pageForParagraph(pages, 5))
        assertEquals(3, pageForParagraph(pages, 99))
    }

    @Test
    fun `an empty page list resolves to the first page`() {
        assertEquals(0, pageForParagraph(emptyList(), 4))
    }

    // ------------------------------------------------------------------ vertical mode

    @Test
    fun `vertical items drop blank paragraphs but keep the numbering`() {
        val items = verticalItems(
            listOf(
                ContentBlock.Paragraph("第一段。"),
                ContentBlock.Paragraph(""),
                ContentBlock.Illustration("http://img/1.jpg"),
                ContentBlock.Paragraph("第二段。"),
            ),
        )

        assertEquals(3, items.size)
        assertEquals(0, (items[0] as VerticalItem.Prose).paragraphIndex)
        assertTrue(items[1] is VerticalItem.Illustration)
        // The blank paragraph still counted, so the second real paragraph is number two.
        assertEquals(2, (items[2] as VerticalItem.Prose).paragraphIndex)
    }

    /** A position restored from an illustration's row lands on the paragraph it follows. */
    @Test
    fun `an illustration reports the paragraph before it`() {
        val items = verticalItems(
            listOf(
                ContentBlock.Paragraph("第一段。"),
                ContentBlock.Paragraph("第二段。"),
                ContentBlock.Illustration("http://img/1.jpg"),
                ContentBlock.Paragraph("第三段。"),
            ),
        )

        assertEquals(1, paragraphIndexAt(items, 2))
        assertEquals(0, paragraphIndexAt(items, 0))
        assertEquals(2, paragraphIndexAt(items, 3))
    }

    /** An illustration before any paragraph has nothing before it, so it reports the beginning. */
    @Test
    fun `a leading illustration reports the start of the chapter`() {
        val items = verticalItems(
            listOf(
                ContentBlock.Illustration("http://img/1.jpg"),
                ContentBlock.Paragraph("第一段。"),
            ),
        )

        assertEquals(0, paragraphIndexAt(items, 0))
    }

    @Test
    fun `list positions and content positions differ by the leading trail`() {
        assertEquals(0, verticalListIndex(0, hasLeadingTrail = false))
        assertEquals(3, verticalListIndex(2, hasLeadingTrail = true))

        assertEquals(0, verticalItemIndex(0, hasLeadingTrail = false, itemCount = 5))
        // Scrolled onto the leading 上一章 button: still the first item, not a negative index.
        assertEquals(0, verticalItemIndex(0, hasLeadingTrail = true, itemCount = 5))
        assertEquals(2, verticalItemIndex(3, hasLeadingTrail = true, itemCount = 5))
        // Scrolled past the end, onto the trailing 下一章 button.
        assertEquals(4, verticalItemIndex(9, hasLeadingTrail = false, itemCount = 5))
    }

    /** Restoring a paragraph in the vertical reader lands on the item that holds it. */
    @Test
    fun `a paragraph resolves to the vertical item that shows it`() {
        val items = verticalItems(
            listOf(
                ContentBlock.Paragraph("第一段。"),
                ContentBlock.Paragraph("第二段。"),
                ContentBlock.Illustration("http://img/1.jpg"),
                ContentBlock.Paragraph("第三段。"),
            ),
        )

        assertEquals(0, verticalItemIndexForParagraph(items, 0))
        assertEquals(1, verticalItemIndexForParagraph(items, 1))
        assertEquals(3, verticalItemIndexForParagraph(items, 2))
        assertEquals(3, verticalItemIndexForParagraph(items, 9))
        assertEquals(0, verticalItemIndexForParagraph(emptyList(), 4))
    }
}
