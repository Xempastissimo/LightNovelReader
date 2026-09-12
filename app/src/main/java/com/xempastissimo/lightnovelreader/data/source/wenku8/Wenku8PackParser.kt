package com.xempastissimo.lightnovelreader.data.source.wenku8

import com.xempastissimo.lightnovelreader.data.source.PackSlice
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import java.nio.charset.Charset

/**
 * One heading of a pack plus the byte range of the body that follows it.
 *
 * The range addresses the pack's **bytes**, not its characters: it stays valid after the
 * pack has been written to disk, so a chapter can be read back without decoding the whole
 * book again.
 *
 * [hasText] is decided at scan time and is not simply `length > 0`: an illustration chapter
 * is published as a heading followed by blank lines, so its range is a few bytes of line
 * breaks and yet there is nothing in it to read.
 */
class PackSection(val heading: String, val offset: Int, val length: Int, val hasText: Boolean)

/** A pack matched against a catalogue: the chapters it holds, and what it could not place. */
class PackMatch(
    /** Chapter id -> body range, for the chapters the pack actually carries text for. */
    val slices: Map<Int, PackSlice>,
    /** How many catalogue chapters [slices] covers. */
    val coveredChapters: Int,
    /** Headings no catalogue chapter claimed — the pack lags or leads the site. */
    val unmatchedHeadings: Int,
)

/**
 * Reads the site's whole-book text pack.
 *
 * The format was verified against the live pack (see README 「整本打包下载」):
 *
 * ```
 * ★☆★☆★☆轻小说文库(Www.WenKu8.Com)☆★☆★☆★
 * <书名>
 *
 * 第一卷 序章 在战场上绽放的红色虞美人
 *
 *     台版 转自 轻之国度
 * ```
 *
 * So the first two lines are a banner and the book's title, a **heading** starts at column
 * zero, and every body line is indented by four half-width spaces with blank lines between
 * paragraphs. A heading is the catalogue's volume title and chapter title joined by a
 * single space.
 *
 * One caveat, measured on the live site: the page and the pack spell the separator *inside*
 * a chapter title differently (`你的气息•辛的状况` on the page, `你的气息·辛的状况` in the
 * pack). Matching headings verbatim therefore loses about 3% of a book's chapters; with
 * [normalizeHeading] the sample book matched 270 out of 270.
 *
 * Illustration chapters are present as headings with an empty body — the pack carries no
 * images, exactly like the site's own txt — so they produce no slice and fall back to the
 * online chapter, which does have them.
 */
object Wenku8PackParser {

    private const val NEWLINE = '\n'.code.toByte()
    private const val CARRIAGE_RETURN = '\r'.code.toByte()
    private const val SPACE = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()

    private const val BULLET = '\u00b7'
    private val BULLET_JOIN = Regex("""\s*·\s*""")
    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * Splits [bytes] into headings and body ranges.
     *
     * A line is a heading when it starts at column zero; blank lines and indented lines
     * belong to the heading above them. The pack's banner and title lines are recognised
     * structurally (a `<...>` line, or one naming the site) and skipped, so the first
     * section is always a real chapter.
     */
    fun scan(bytes: ByteArray, charset: Charset): List<PackSection> {
        val sections = ArrayList<PackSection>(512)
        var lineStart = 0
        var pendingHeading: String? = null
        var bodyStart = 0

        fun closeSection(end: Int) {
            val heading = pendingHeading ?: return
            val start = bodyStart.coerceAtMost(bytes.size)
            val stop = end.coerceIn(start, bytes.size)
            sections.add(PackSection(heading, start, stop - start, hasText(bytes, start, stop)))
            pendingHeading = null
        }

        var index = 0
        while (true) {
            val atEnd = index >= bytes.size
            if (atEnd || bytes[index] == NEWLINE) {
                var end = index
                if (end > lineStart && bytes[end - 1] == CARRIAGE_RETURN) end--

                val contentStart = skipIndent(bytes, lineStart, end)
                if (contentStart < end && !isIndented(bytes, lineStart)) {
                    // A non-blank, non-indented line: the previous chapter's body ends
                    // where this heading begins.
                    closeSection(lineStart)
                    val text = String(bytes, lineStart, end - lineStart, charset).trim()
                    pendingHeading = text.takeUnless { isHeaderLine(it) }
                    bodyStart = index + 1
                }

                lineStart = index + 1
            }
            if (atEnd) break
            index++
        }

        closeSection(bytes.size)
        return sections
    }

    /**
     * Places every section on a catalogue chapter.
     *
     * Matching is tried with `卷名 + 空格 + 章节名` first, then with the chapter title
     * alone — the latter is what a catalogue whose volume cell is missing produces, and it
     * only ever sees sections the pack published without a volume prefix. Sections are
     * consumed in file order, so two chapters that share a title take the two sections in
     * the order the pack lists them instead of both claiming the first one.
     */
    fun match(detail: BookDetail, sections: List<PackSection>): PackMatch {
        if (sections.isEmpty() || detail.volumes.isEmpty()) {
            return PackMatch(emptyMap(), 0, sections.size)
        }

        val byHeading = HashMap<String, ArrayDeque<PackSection>>(sections.size * 2)
        for (section in sections) {
            byHeading.getOrPut(normalizeHeading(section.heading)) { ArrayDeque() }.addLast(section)
        }

        val slices = LinkedHashMap<Int, PackSlice>()
        var consumed = 0
        for (volume in detail.volumes) {
            for (chapter in volume.chapters) {
                val section = byHeading[normalizeHeading("${volume.title} ${chapter.title}")]
                    ?.removeFirstOrNull()
                    ?: byHeading[normalizeHeading(chapter.title)]?.removeFirstOrNull()
                    ?: continue
                consumed++
                // An empty body is an illustration chapter: recording it would make the
                // reader serve a blank page instead of the online chapter, which has the
                // images. It is reported as "not covered" instead.
                if (section.hasText) {
                    slices[chapter.chapterId] = PackSlice(section.offset, section.length)
                }
            }
        }
        return PackMatch(slices, slices.size, sections.size - consumed)
    }

    /**
     * Canonical form of a heading, for comparing the page's spelling with the pack's.
     *
     * Collapses every kind of space to one, unifies the bullet characters the two sides
     * disagree about (`•` `・` `‧` `‧` `●` `▪` all become `·`, with the spaces around them
     * dropped), and removes zero-width characters and a BOM.
     */
    fun normalizeHeading(raw: String): String {
        val mapped = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                isBullet(ch) -> mapped.append(BULLET)
                ch == '\u3000' || ch == '\u00a0' || ch.isWhitespace() -> mapped.append(' ')
                ch == '\uff0e' || ch == '\u3002' -> mapped.append('.')
                ch == '\u200b' || ch == '\u200c' || ch == '\u200d' || ch == '\ufeff' -> Unit
                else -> mapped.append(ch)
            }
        }
        return mapped.toString()
            .replace(BULLET_JOIN, BULLET.toString())
            .replace(WHITESPACE_RUN, " ")
            .trim()
    }

    private fun isBullet(ch: Char): Boolean = when (ch) {
        '\u2022', '\u30fb', '\u2027', '\u2023', '\u25cf', '\u25aa', BULLET -> true
        else -> false
    }

    /**
     * The pack's own banner (`…轻小说文库(Www.WenKu8.Com)…`) and title line (`<书名>`).
     *
     * Recognised by their shape rather than by position, so a pack that grows another
     * header line still parses.
     */
    private fun isHeaderLine(text: String): Boolean =
        (text.startsWith("<") && text.endsWith(">")) || text.contains("wenku8", ignoreCase = true)

    private fun isIndented(bytes: ByteArray, start: Int): Boolean {
        if (start >= bytes.size) return false
        return when (bytes[start]) {
            SPACE, TAB -> true
            // U+3000 IDEOGRAPHIC SPACE, in either of the two encodings the packs use.
            else -> (start + 1 < bytes.size &&
                bytes[start] == 0xA1.toByte() && bytes[start + 1] == 0xA1.toByte()) ||
                (start + 2 < bytes.size &&
                    bytes[start] == 0xE3.toByte() &&
                    bytes[start + 1] == 0x80.toByte() &&
                    bytes[start + 2] == 0x80.toByte())
        }
    }

    /** First byte of the line that is not indentation; the line's end when it is all blank. */
    private fun skipIndent(bytes: ByteArray, start: Int, end: Int): Int {
        var index = start
        while (index < end && (bytes[index] == SPACE || bytes[index] == TAB)) index++
        return index
    }

    /**
     * Whether a body range holds anything but line breaks.
     *
     * An illustration chapter is a heading followed by blank lines, so the range exists and
     * contains nothing — which is the difference between "the pack has this chapter" and "the
     * pack has no text for it".
     */
    private fun hasText(bytes: ByteArray, start: Int, end: Int): Boolean {
        for (index in start until end) {
            when (bytes[index]) {
                SPACE, TAB, NEWLINE, CARRIAGE_RETURN -> Unit
                else -> return true
            }
        }
        return false
    }
}
