package com.xempastissimo.lightnovelreader.ui.screen.reader

import com.xempastissimo.lightnovelreader.domain.model.ContentBlock

/** One screen of the reader: either a block of prose or a full-bleed illustration. */
sealed interface ReaderPageContent {
    /**
     * A page's prose.
     *
     * [paragraphs] is one entry per paragraph *fragment* rather than one joined string, because
     * the page renders one `Text` per entry: `TextAlign.Justify` does not stretch the last line
     * of a text block, so a page drawn as a single `Text` would stretch every paragraph's final
     * line except the page's own last one.
     *
     * [startParagraph] is the index, in `ChapterContent.paragraphs`, of the paragraph the first
     * fragment belongs to — the anchor a bookmark or a saved position is restored by.
     */
    data class Prose(val paragraphs: List<String>, val startParagraph: Int) : ReaderPageContent

    data class Illustration(val url: String) : ReaderPageContent
}

/**
 * What one paragraph laid out to.
 *
 * [heightPx] is the paragraph's real laid-out height, and is what makes the packing trustworthy:
 * a rendered text is not always exactly `lines × lineHeight` — font padding adds a fixed amount
 * above and below, and whether it is applied depends on the platform's text defaults. Deriving
 * the difference instead of assuming it keeps the page arithmetic true either way.
 */
internal data class MeasuredParagraph(val lineEnds: List<Int>, val heightPx: Int)

/**
 * A chapter block together with how it laid out at the reader's current width.
 *
 * The measurement itself is Compose's job (`TextMeasurer`), so it happens in the screen; what
 * is kept here is only the *result* that packing needs — where the lines break and how tall the
 * paragraph came out — which is what lets the page-splitting rules be plain Kotlin and be
 * tested on the JVM.
 */
internal sealed interface LaidOutBlock {
    /**
     * @param lineEnds exclusive end offset of each laid-out line; the last entry is the text's
     *   length. Lines are contiguous, so line `i` starts where line `i - 1` ended.
     * @param linePadPx height one rendered fragment carries on top of its lines — font padding,
     *   which is charged once per `Text` and therefore once per fragment, not once per line.
     * @param paragraphIndex index of this paragraph in `ChapterContent.paragraphs`, blanks
     *   included — the numbering the reader's own anchors use.
     */
    data class Prose(
        val text: String,
        val lineEnds: List<Int>,
        val linePadPx: Int,
        val paragraphIndex: Int,
    ) : LaidOutBlock

    data class Illustration(val url: String) : LaidOutBlock
}

/**
 * Turns a chapter's blocks into laid-out ones, numbering prose blocks the way
 * `ChapterContent.paragraphs` does.
 *
 * [measure] is expected to be `TextMeasurer.measure` in the app. It is a parameter so that the
 * numbering — the part that has to agree with bookmarks and saved positions — can be asserted
 * without a device or a composition.
 *
 * Blank paragraphs are numbered but not emitted: they are nothing to draw, and the reader has
 * never shown them.
 */
internal fun laidOutBlocks(
    blocks: List<ContentBlock>,
    lineHeightPx: Int,
    measure: (text: String) -> MeasuredParagraph,
): List<LaidOutBlock> {
    val laid = ArrayList<LaidOutBlock>(blocks.size)
    var paragraphIndex = 0
    for (block in blocks) {
        when (block) {
            is ContentBlock.Paragraph -> {
                val index = paragraphIndex++
                if (block.text.isBlank()) continue
                val measured = measure(block.text)
                if (measured.lineEnds.isEmpty()) continue
                val lines = measured.lineEnds.size * lineHeightPx
                val pad = (measured.heightPx - lines).coerceAtLeast(0)
                laid.add(LaidOutBlock.Prose(block.text, measured.lineEnds, pad, index))
            }

            is ContentBlock.Illustration -> laid.add(LaidOutBlock.Illustration(block.url))
        }
    }
    return laid
}

/**
 * Packs laid-out blocks into pages that each fit [maxHeightPx].
 *
 * A page's height is the sum of its fragments' own heights — `lines × [lineHeightPx]` plus the
 * fragment's measured font padding — with one [paragraphSpacingPx] between consecutive
 * fragments. That is the same arithmetic the page itself performs, which is why a page can be
 * trusted not to overflow.
 *
 * A paragraph too tall for a whole page is split at a line boundary of the measured layout, and
 * the pieces are rendered as plain character ranges — the page wraps them again itself. Greedy
 * wrapping needs no more lines than the balanced layout the split came from, so a page can only
 * come out shorter than it was packed.
 *
 * The one way to exceed [maxHeightPx] is a window shorter than a single line, where one line is
 * placed anyway so that packing always terminates; the reader reserves a safety margin for
 * exactly this kind of rounding.
 */
internal fun paginateBlocks(
    blocks: List<LaidOutBlock>,
    maxHeightPx: Int,
    lineHeightPx: Int,
    paragraphSpacingPx: Int,
): List<ReaderPageContent> {
    if (blocks.isEmpty()) return emptyList()

    val pages = ArrayList<ReaderPageContent>()
    val fragments = ArrayList<String>()
    var used = 0
    var startParagraph = 0

    fun flush() {
        if (fragments.isNotEmpty()) {
            pages.add(ReaderPageContent.Prose(fragments.toList(), startParagraph))
            fragments.clear()
            used = 0
        }
    }

    /** What a fragment of [lines] lines of [block] would cost on the page as it stands. */
    fun costOf(block: LaidOutBlock.Prose, lines: Int): Int {
        val body = lines * lineHeightPx + block.linePadPx
        return if (fragments.isEmpty()) body else used + paragraphSpacingPx + body
    }

    fun place(block: LaidOutBlock.Prose, from: Int, count: Int) {
        val body = count * lineHeightPx + block.linePadPx
        if (fragments.isEmpty()) {
            startParagraph = block.paragraphIndex
            used = body
        } else {
            used += paragraphSpacingPx + body
        }
        fragments.add(block.fragment(from, count))
    }

    for (block in blocks) {
        when (block) {
            is LaidOutBlock.Illustration -> {
                flush()
                pages.add(ReaderPageContent.Illustration(block.url))
            }

            is LaidOutBlock.Prose -> {
                val lineCount = block.lineEnds.size
                if (lineCount == 0) continue

                var startLine = 0
                while (startLine < lineCount) {
                    val remaining = lineCount - startLine
                    val fitsHere = costOf(block, remaining) <= maxHeightPx
                    val fitsAPage = remaining * lineHeightPx + block.linePadPx <= maxHeightPx
                    when {
                        fitsHere -> {
                            place(block, startLine, remaining)
                            startLine = lineCount
                        }

                        // Not too tall for a page — it simply does not fit on *this* one. An
                        // empty page always satisfies `fitsHere` when `fitsAPage` holds, so
                        // this cannot spin.
                        fitsAPage -> flush()

                        else -> {
                            // Taller than a whole page: take whole lines up to what is left.
                            val room = maxHeightPx -
                                if (fragments.isEmpty()) 0 else used + paragraphSpacingPx
                            var take = if (lineHeightPx > 0) room / lineHeightPx else 0
                            if (take < 1) {
                                if (fragments.isNotEmpty()) {
                                    flush()
                                    continue
                                }
                                take = 1
                            }
                            take = take.coerceAtMost(remaining)
                            place(block, startLine, take)
                            startLine += take
                            flush()
                        }
                    }
                }
            }
        }
    }
    flush()
    return pages
}

/**
 * Which page holds [paragraph].
 *
 * The page whose first fragment belongs to that paragraph, or the last page that starts at or
 * before it — which is how a paragraph that was split across two pages resolves to the page the
 * reader would call its beginning. Illustration pages carry no paragraph and are skipped.
 */
internal fun pageForParagraph(pages: List<ReaderPageContent>, paragraph: Int): Int {
    if (pages.isEmpty()) return 0
    var found = 0
    for ((index, page) in pages.withIndex()) {
        val start = (page as? ReaderPageContent.Prose)?.startParagraph ?: continue
        if (start <= paragraph) found = index
    }
    return found.coerceIn(0, pages.size - 1)
}

/**
 * The characters of lines [from]..[last], with nothing inserted between them.
 *
 * Deliberately **not** joined with newlines. The line boundaries came from a measured layout, and
 * a measured layout is not necessarily the greedy one: `TextAlign.Justify` breaks lines so they
 * balance, and a balanced break can fall a long way short of the margin — a single character on a
 * line of its own is a legitimate result of it. Writing those boundaries back into the text as
 * `\n` turned them into *hard* breaks, so the page reproduced the balanced layout's ragged edges
 * instead of filling its own lines. That is what a page of orphans looks like.
 *
 * Letting the page wrap the characters itself is also what keeps the packing honest: greedy
 * wrapping never needs more lines than a balanced one, so a page can only come out **shorter**
 * than it was packed, never taller.
 */
private fun LaidOutBlock.Prose.fragment(from: Int, count: Int): String {
    val last = (from + count - 1).coerceAtMost(lineEnds.size - 1)
    val start = if (from == 0) 0 else lineEnds[from - 1]
    val end = lineEnds[last]
    return text.substring(start.coerceIn(0, text.length), end.coerceIn(start, text.length))
}

/** One entry of the 上下滚动 reader's list: a paragraph or an illustration. */
internal sealed interface VerticalItem {
    /** [paragraphIndex] indexes `ChapterContent.paragraphs`. */
    data class Prose(val text: String, val paragraphIndex: Int) : VerticalItem

    data class Illustration(val url: String) : VerticalItem
}

/** The vertical reader's blocks, blanks dropped, each prose block carrying its paragraph index. */
internal fun verticalItems(blocks: List<ContentBlock>): List<VerticalItem> {
    val items = ArrayList<VerticalItem>(blocks.size)
    var paragraphIndex = 0
    for (block in blocks) {
        when (block) {
            is ContentBlock.Paragraph -> {
                val index = paragraphIndex++
                if (block.text.isBlank()) continue
                items.add(VerticalItem.Prose(block.text, index))
            }

            is ContentBlock.Illustration -> items.add(VerticalItem.Illustration(block.url))
        }
    }
    return items
}

/**
 * The paragraph a list position belongs to.
 *
 * An illustration belongs to the paragraph before it, which is where a position restored from
 * its row should land; the first item has nothing before it, so it reports paragraph 0.
 */
internal fun paragraphIndexAt(items: List<VerticalItem>, itemIndex: Int): Int {
    if (items.isEmpty()) return 0
    val index = itemIndex.coerceIn(0, items.size - 1)
    for (position in index downTo 0) {
        val prose = items[position] as? VerticalItem.Prose ?: continue
        return prose.paragraphIndex
    }
    return 0
}

/** List position of the vertical reader's [itemIndex]-th content item. */
internal fun verticalListIndex(itemIndex: Int, hasLeadingTrail: Boolean): Int =
    itemIndex + if (hasLeadingTrail) 1 else 0

/** The content item a list position shows, clamped to the content the list actually holds. */
internal fun verticalItemIndex(listIndex: Int, hasLeadingTrail: Boolean, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    val raw = listIndex - if (hasLeadingTrail) 1 else 0
    return raw.coerceIn(0, itemCount - 1)
}

/**
 * The content item a paragraph sits in, for restoring a position in the vertical reader.
 *
 * The last item that starts at or before the paragraph, so a paragraph inside a run of
 * illustrations resolves to the prose it belongs to rather than past it.
 */
internal fun verticalItemIndexForParagraph(items: List<VerticalItem>, paragraph: Int): Int {
    if (items.isEmpty()) return 0
    var found = 0
    for ((index, item) in items.withIndex()) {
        val prose = item as? VerticalItem.Prose ?: continue
        if (prose.paragraphIndex <= paragraph) found = index
    }
    return found
}
