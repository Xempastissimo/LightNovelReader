package com.xempastissimo.lightnovelreader.core.html

/**
 * A deliberately small, dependency-free HTML DOM.
 *
 * The project cannot rely on Jsoup (see the offline/registry constraints in
 * README), so this file provides just enough structure to run CSS-like queries
 * against the pages of a book source: element tree, attributes, text, and a
 * fault-tolerant tokenizer.
 *
 * Supported selector syntax (`querySelectorAll` / [Element.select]):
 * - `tag`, `#id`, `.class`, `*`
 * - compounds: `div#content.vcss`
 * - descendant: `table tr td`
 * - child: `ul > li`
 * - comma separated groups: `h1, h3`
 *
 * Attribute selectors (`[href]`, `[href^=...]`) and pseudo classes are *not*
 * supported. Use [Element.attr] / [Element.children] for those cases.
 */
class Element(
    val tag: String,
    var parent: Element? = null,
) {

    private val attributeMap = LinkedHashMap<String, String>(8)
    val children = ArrayList<Element>(8)
    val ownText = StringBuilder()

    val attrs: Map<String, String> get() = attributeMap

    fun attr(name: String): String? = attributeMap[name]

    fun hasAttr(name: String): Boolean = attributeMap.containsKey(name)

    fun classes(): Set<String> {
        val raw = attributeMap["class"] ?: attributeMap["CLASS"] ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(' ', '\t', '\n', '\r', '\u000c').filter { it.isNotEmpty() }.toSet()
    }

    fun hasClass(name: String): Boolean = classes().contains(name)

    fun id(): String? = attributeMap["id"]

    internal fun setAttr(name: String, value: String) {
        attributeMap[name] = value
    }

    /** All descendant elements (document order), including nested ones. */
    fun descendants(): Sequence<Element> = sequence {
        for (child in children) {
            yield(child)
            yieldAll(child.descendants())
        }
    }

    /** First descendant (depth-first, document order) matching [tag], or null. */
    fun firstByTag(tag: String): Element? {
        val wanted = tag.lowercase()
        return descendants().firstOrNull { it.tag == wanted }
    }

    /**
     * Concatenated text of this element and its descendants.
     * Block level boundaries are preserved as newlines by [textWithBreaks].
     */
    fun text(): String = buildString {
        append(ownText)
        for (child in children) append(child.text())
    }

    /** Text where nested block elements start on a new line. */
    fun textWithBreaks(): String = buildString {
        appendTextOf(this@Element, this)
    }

    fun select(selector: String): List<Element> = CssSelector.parse(selector).queryFrom(this)

    fun selectFirst(selector: String): Element? = select(selector).firstOrNull()

    fun childElements(): List<Element> = children

    private fun appendTextOf(element: Element, out: StringBuilder) {
        out.append(element.ownText)
        val block = element.tag == "br" || CssSelector.isBlockTag(element.tag)
        for (child in element.children) {
            if (block) out.append('\n')
            appendTextOf(child, out)
        }
    }

    override fun toString(): String = "<$tag${if (attributeMap.isEmpty()) "" else " " + attributeMap.entries.joinToString(" ") { "${it.key}=${it.value}" }}>"
}

object Html {

    private val VOID_ELEMENTS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /** Tags whose content is never treated as markup. */
    private val RAW_TEXT_ELEMENTS = setOf("script", "style", "textarea")

    fun parse(html: String): Element {
        val root = Element("#document")
        val stack = ArrayList<Element>(32).apply { add(root) }

        var index = 0
        val source = stripTitleAttributes(html)
        val length = source.length

        while (index < length) {
            val lt = source.indexOf('<', index)
            if (lt < 0) {
                appendText(stack.last(), source, index, length)
                break
            }
            if (lt > index) appendText(stack.last(), source, index, lt)

            when {
                source.startsWith("<!--", lt) -> {
                    val end = source.indexOf("-->", lt + 4)
                    index = if (end < 0) length else end + 3
                }

                source.startsWith("<!", lt) || source.startsWith("<?", lt) -> {
                    val end = source.indexOf('>', lt)
                    index = if (end < 0) length else end + 1
                }

                source.startsWith("</", lt) -> {
                    val end = source.indexOf('>', lt)
                    if (end < 0) {
                        index = length
                    } else {
                        val name = source.substring(lt + 2, end).trim().lowercase()
                        closeElement(stack, name)
                        index = end + 1
                    }
                }

                else -> {
                    val nameEnd = readNameEnd(source, lt + 1)
                    if (nameEnd == lt + 1) {
                        // Not a tag after all, treat the '<' as text.
                        appendText(stack.last(), source, lt, lt + 1)
                        index = lt + 1
                    } else {
                        val name = source.substring(lt + 1, nameEnd).lowercase()
                        val tagEnd = findTagEnd(source, nameEnd)

                        if (name in RAW_TEXT_ELEMENTS) {
                            val close = source.indexOf("</$name", tagEnd + 1)
                            val contentEnd = if (close < 0) length else close
                            val element = Element(name, stack.last())
                            parseAttributes(source, nameEnd, tagEnd, element)
                            stack.last().children.add(element)
                            if (contentEnd > tagEnd + 1) {
                                element.ownText.append(source, tagEnd + 1, contentEnd)
                            }
                            index = if (close < 0) length else close
                        } else if (name in VOID_ELEMENTS) {
                            val element = Element(name, stack.last())
                            parseAttributes(source, nameEnd, tagEnd, element)
                            stack.last().children.add(element)
                            index = tagEnd + 1
                        } else {
                            val selfClosing = tagEnd > lt && source[tagEnd - 1] == '/'
                            val element = Element(name, stack.last())
                            parseAttributes(source, nameEnd, tagEnd, element)
                            stack.last().children.add(element)
                            if (selfClosing) {
                                index = tagEnd + 1
                            } else {
                                stack.add(element)
                                index = tagEnd + 1
                            }
                        }
                    }
                }
            }
        }
        return root
    }

    /**
     * Defuses a quirk of the source's pages: they carry a form input whose
     * `name` attribute is literally `title`, which a tag scanner can mistake for
     * the opening `<title>` element and then "close" on it, truncating the rest
     * of the document. Removing those attributes before scanning loses nothing.
     */
    private val TITLE_ATTRIBUTE = Regex("""\sname\s*=\s*["']?title["']?""", RegexOption.IGNORE_CASE)

    private fun stripTitleAttributes(html: String): String =
        if (html.contains("name=\"title\"") || html.contains("name='title'") || html.contains("name=title")) {
            TITLE_ATTRIBUTE.replace(html, "")
        } else {
            html
        }

    fun parseFragment(html: String): Element = parse(html)

    /** Decodes the HTML entities that actually show up on book source pages. */
    fun decodeEntities(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '&') {
                out.append(ch)
                i++
                continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                out.append(ch)
                i++
                continue
            }
            val entity = raw.substring(i + 1, semi)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                out.append(ch)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String? {
        if (entity.isEmpty()) return null
        if (entity[0] == '#') {
            val code = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull()
            }
            return code?.takeIf { it > 0 && it <= 0x10FFFF }?.let { String(Character.toChars(it)) }
        }
        return NAMED_ENTITIES[entity.lowercase()]
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00a0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
        "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122", "hellip" to "\u2026",
        "mdash" to "\u2014", "ndash" to "\u2013", "middot" to "\u00b7", "bull" to "\u2022",
        "laquo" to "\u00ab", "raquo" to "\u00bb", "ldquo" to "\u201c", "rdquo" to "\u201d",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "times" to "\u00d7", "divide" to "\u00f7",
        "deg" to "\u00b0", "plusmn" to "\u00b1", "sup2" to "\u00b2", "sup3" to "\u00b3",
        "frac12" to "\u00bd", "sect" to "\u00a7", "para" to "\u00b6", "dagger" to "\u2020",
        "yen" to "\u00a5", "euro" to "\u20ac", "pound" to "\u00a3", "cent" to "\u00a2",
        "szlig" to "\u00df", "agrave" to "\u00e0", "aacute" to "\u00e1", "eacute" to "\u00e9",
        "egrave" to "\u00e8", "uuml" to "\u00fc", "ouml" to "\u00f6", "auml" to "\u00e4",
        "nbsp;" to "\u00a0",
    )

    private fun appendText(target: Element, html: String, from: Int, to: Int) {
        if (to <= from) return
        val chunk = html.substring(from, to)
        if (chunk.isEmpty()) return
        target.ownText.append(decodeEntities(chunk))
    }

    private fun readNameEnd(html: String, start: Int): Int {
        var i = start
        while (i < html.length) {
            val c = html[i]
            val ok = c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '!'
            if (!ok) break
            i++
        }
        return i
    }

    private fun findTagEnd(html: String, from: Int): Int {
        var quote = '\u0000'
        var i = from
        while (i < html.length) {
            val c = html[i]
            when {
                quote != '\u0000' -> if (c == quote) quote = '\u0000'
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return html.length - 1
    }

    private fun parseAttributes(html: String, from: Int, to: Int, element: Element) {
        var i = from
        val end = minOf(to, html.length)
        while (i < end) {
            while (i < end && html[i].isWhitespace()) i++
            val nameStart = i
            while (i < end) {
                val c = html[i]
                if (c.isWhitespace() || c == '=' || c == '/' || c == '>') break
                i++
            }
            if (i == nameStart) {
                i++
                continue
            }
            val name = html.substring(nameStart, i).lowercase()
            while (i < end && html[i].isWhitespace()) i++
            if (i < end && html[i] == '=') {
                i++
                while (i < end && html[i].isWhitespace()) i++
                if (i < end && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i]
                    val valueStart = i + 1
                    var valueEnd = valueStart
                    while (valueEnd < end && html[valueEnd] != quote) valueEnd++
                    element.setAttr(name, decodeEntities(html.substring(valueStart, minOf(valueEnd, end))))
                    i = if (valueEnd < end) valueEnd + 1 else end
                } else {
                    val valueStart = i
                    var valueEnd = i
                    while (valueEnd < end && !html[valueEnd].isWhitespace() && html[valueEnd] != '>') valueEnd++
                    element.setAttr(name, decodeEntities(html.substring(valueStart, valueEnd)))
                    i = valueEnd
                }
            } else {
                element.setAttr(name, "")
            }
        }
    }

    private fun closeElement(stack: MutableList<Element>, name: String) {
        if (name == "#document") return
        // Void elements have no end tag; a stray `</br>` (which the source emits)
        // must not pop the stack, or the rest of the document is lost.
        if (name in VOID_ELEMENTS) return
        for (i in stack.indices.reversed()) {
            if (stack[i].tag == name) {
                while (stack.size > i) stack.removeAt(stack.size - 1)
                return
            }
        }
        // Stray closing tag: ignore.
    }
}
