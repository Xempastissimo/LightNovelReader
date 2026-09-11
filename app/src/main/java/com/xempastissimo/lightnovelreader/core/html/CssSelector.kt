package com.xempastissimo.lightnovelreader.core.html

/**
 * Minimal CSS selector engine over [Element].
 *
 * Grammar: `group := compound (combinator compound)*`, groups split on `,`,
 * combinator is either descendant (whitespace) or child (`>`).
 *
 * Supported inside a compound: `tag`, `*`, `#id`, `.class`, and attribute tests
 * (`[href]`, `[href*=x]`, `[href^=/book/]`, `[href$=.htm]`). Pseudo classes are
 * not supported; use [Element.attr] / [Element.children] for anything exotic.
 */
internal class CssSelector private constructor(
    private val groups: List<List<Step>>,
) {

    internal class Step(
        val combinator: Char, // ' ' or '>'
        val tag: String?, // null == '*'
        val id: String?,
        val classes: List<String>,
        val attributes: List<AttrTest>,
    ) {
        fun matches(element: Element): Boolean {
            if (tag != null && element.tag != tag) return false
            if (id != null && element.id() != id) return false
            if (classes.isNotEmpty()) {
                val elementClasses = element.classes()
                for (c in classes) if (!elementClasses.contains(c)) return false
            }
            for (test in attributes) if (!test.matches(element)) return false
            return true
        }
    }

    /** One `[name op value]` test; `op` is null for a presence check. */
    internal class AttrTest(val name: String, val op: String?, val value: String) {

        fun matches(element: Element): Boolean {
            val actual = element.attr(name) ?: return false
            if (op == null) return true
            return when (op) {
                "=" -> actual.equals(value, ignoreCase = true)
                "*=" -> actual.contains(value, ignoreCase = true)
                "^=" -> actual.startsWith(value, ignoreCase = true)
                "$=" -> actual.endsWith(value, ignoreCase = true)
                "~=" -> actual.split(' ', '\t', '\n').any { it.equals(value, ignoreCase = true) }
                "|=" -> actual.equals(value, ignoreCase = true) || actual.startsWith("$value-", ignoreCase = true)
                else -> false
            }
        }
    }

    fun queryFrom(scope: Element): List<Element> {
        val result = LinkedHashSet<Element>()
        for (group in groups) result.addAll(matchGroup(scope, group))
        return result.toList()
    }

    private fun matchGroup(scope: Element, group: List<Step>): List<Element> {
        var candidates = ArrayList<Element>()
        collectCandidates(scope, group.first(), candidates)
        if (group.size == 1) return candidates

        for (i in 1 until group.size) {
            val step = group[i]
            val next = ArrayList<Element>()
            val seen = HashSet<Element>()
            for (current in candidates) {
                if (step.combinator == '>') {
                    for (child in current.children) {
                        if (step.matches(child) && seen.add(child)) next.add(child)
                    }
                } else {
                    for (descendant in current.descendants()) {
                        if (step.matches(descendant) && seen.add(descendant)) next.add(descendant)
                    }
                }
            }
            candidates = next
            if (candidates.isEmpty()) return emptyList()
        }
        return candidates
    }

    private fun collectCandidates(scope: Element, step: Step, out: MutableList<Element>) {
        for (descendant in scope.descendants()) {
            if (step.matches(descendant)) out.add(descendant)
        }
    }

    companion object {

        private val BLOCK_TAGS = setOf(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt",
            "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4",
            "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section",
            "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
        )

        fun isBlockTag(tag: String): Boolean = BLOCK_TAGS.contains(tag)

        private val CACHE = HashMap<String, CssSelector>(32)

        fun parse(selector: String): CssSelector = synchronized(CACHE) {
            CACHE.getOrPut(selector) { CssSelector(parseGroups(selector)) }
        }

        private fun parseGroups(selector: String): List<List<Step>> {
            require(selector.isNotBlank()) { "Empty selector" }
            return selector.split(',').map { parseGroup(it) }
        }

        private fun parseGroup(group: String): List<Step> {
            val steps = ArrayList<Step>(4)
            var combinator = ' '
            val token = StringBuilder()

            fun flushToken() {
                if (token.isNotEmpty()) {
                    steps.add(parseCompound(token.toString(), combinator))
                    token.setLength(0)
                    combinator = ' '
                }
            }

            var i = 0
            while (i < group.length) {
                val c = group[i]
                when {
                    c == '>' -> {
                        val trimmed = token.toString().trim()
                        token.setLength(0)
                        token.append(trimmed)
                        flushToken()
                        combinator = '>'
                    }

                    c.isWhitespace() -> {
                        val trimmed = token.toString().trim()
                        token.setLength(0)
                        token.append(trimmed)
                        flushToken()
                    }

                    else -> token.append(c)
                }
                i++
            }
            flushToken()
            return steps
        }

        private fun parseCompound(spec: String, combinator: Char): Step {
            var tag: String? = null
            var id: String? = null
            val classes = ArrayList<String>(2)
            val attributes = ArrayList<AttrTest>(2)

            var i = 0
            while (i < spec.length) {
                when (val c = spec[i]) {
                    '*' -> {
                        i++
                    }

                    '#' -> {
                        val start = i + 1
                        var end = start
                        while (end < spec.length && isIdentChar(spec[end])) end++
                        if (end > start) id = spec.substring(start, end)
                        i = end
                    }

                    '.' -> {
                        val start = i + 1
                        var end = start
                        while (end < spec.length && isIdentChar(spec[end])) end++
                        if (end > start) classes.add(spec.substring(start, end))
                        i = end
                    }

                    '[' -> {
                        val close = spec.indexOf(']', i + 1)
                        if (close < 0) {
                            i = spec.length
                        } else {
                            parseAttributeTest(spec.substring(i + 1, close))?.let { attributes.add(it) }
                            i = close + 1
                        }
                    }

                    else -> {
                        if (!isIdentChar(c)) {
                            i++
                        } else {
                            val start = i
                            var end = start
                            while (end < spec.length && isIdentChar(spec[end])) end++
                            val name = spec.substring(start, end)
                            if (tag == null) tag = name.lowercase() else classes.add(name)
                            i = end
                        }
                    }
                }
            }
            return Step(combinator, tag, id, classes, attributes)
        }

        /** Parses the inside of `[...]`: `href`, `href=x`, `href*=x`, `href^=x`, `href$=x`. */
        private fun parseAttributeTest(body: String): AttrTest? {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null
            for (op in listOf("*=", "^=", "$=", "~=", "|=", "=")) {
                val index = trimmed.indexOf(op)
                if (index <= 0) continue
                val name = trimmed.substring(0, index).trim().lowercase()
                val value = trimmed.substring(index + op.length).trim().trim('"', '\'')
                if (name.isEmpty()) continue
                return AttrTest(name, op, value)
            }
            return AttrTest(trimmed.lowercase(), null, "")
        }

        private fun isIdentChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '-' || c == '_'
    }
}
