package com.xempastissimo.lightnovelreader.core.text

/**
 * Normalises scraped text so the reader and the list screens do not have to
 * deal with page markup artefacts.
 */
object TextCleaner {

    private const val NBSP = '\u00a0'
    private const val IDEOGRAPHIC_SPACE = '\u3000'

    /** Collapses every whitespace run into a single space and trims. */
    fun oneLine(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val builder = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            if (isSpace(ch)) {
                pendingSpace = builder.isNotEmpty()
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Cleans a chapter/description body while preserving paragraph structure:
     * tabs and exotic spaces become spaces, runs of blank lines collapse to one,
     * and leading/trailing blank lines disappear.
     */
    fun paragraphs(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val normalized = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\t', ' ')
            .replace(NBSP, ' ')
            .replace(IDEOGRAPHIC_SPACE, ' ')
            .replace("\u200b", "")
            .replace("\ufeff", "")

        val result = ArrayList<String>(64)
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            if (ch == '\n') {
                flushParagraph(current, result)
                i++
            } else {
                current.append(ch)
                i++
            }
        }
        flushParagraph(current, result)
        return result
    }

    /** Same as [paragraphs] but joined back with single newlines. */
    fun body(raw: String?): String = paragraphs(raw).joinToString("\n")

    private fun flushParagraph(current: StringBuilder, out: MutableList<String>) {
        if (current.isEmpty()) return
        val text = collapseSpaces(current)
        current.setLength(0)
        if (text.isNotEmpty()) out.add(text)
    }

    private fun collapseSpaces(source: CharSequence): String {
        val builder = StringBuilder(source.length)
        var pendingSpace = false
        for (ch in source) {
            if (isSpace(ch)) {
                pendingSpace = builder.isNotEmpty()
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(ch)
            }
        }
        return builder.toString().trim()
    }

    private fun isSpace(ch: Char): Boolean =
        ch == ' ' || ch == NBSP || ch == IDEOGRAPHIC_SPACE || ch == '\t' || ch == '\n' || ch == '\r' || ch.isWhitespace()
}
