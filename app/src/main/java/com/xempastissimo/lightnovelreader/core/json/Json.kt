package com.xempastissimo.lightnovelreader.core.json

/**
 * Tiny JSON writer/reader used for on-device persistence.
 *
 * `org.json` is part of the Android framework and is *not* available to JVM
 * unit tests (calling it throws "not mocked"), and the project must not depend
 * on kotlinx-serialization/Gson. Local storage formats here are small and
 * owned by this app, so a purpose-built codec keeps the data layer testable on
 * the JVM and removes any question about escaping behaviour.
 */
object Json {

    class Obj internal constructor(internal val entries: LinkedHashMap<String, Any?>) {

        constructor() : this(LinkedHashMap())

        fun put(key: String, value: String?): Obj = apply { entries[key] = value }

        fun put(key: String, value: Number?): Obj = apply { entries[key] = value }

        fun put(key: String, value: Boolean?): Obj = apply { entries[key] = value }

        fun put(key: String, value: List<String>?): Obj = apply { entries[key] = value }

        fun put(key: String, value: Obj?): Obj = apply { entries[key] = value }

        fun put(key: String, value: Arr?): Obj = apply { entries[key] = value }

        fun putNull(key: String): Obj = apply { entries[key] = null }

        fun string(key: String, default: String = ""): String = entries[key] as? String ?: default

        fun stringOrNull(key: String): String? = entries[key] as? String

        fun long(key: String, default: Long = 0L): Long = (entries[key] as? Number)?.toLong() ?: default

        fun int(key: String, default: Int = 0): Int = (entries[key] as? Number)?.toInt() ?: default

        fun double(key: String, default: Double = 0.0): Double = (entries[key] as? Number)?.toDouble() ?: default

        fun boolean(key: String, default: Boolean = false): Boolean = entries[key] as? Boolean ?: default

        /**
         * String elements of an array value.
         *
         * [Arr] is deliberately not a `List`, so both shapes have to be accepted
         * here: an [Arr] built by this codec, and a plain `List<String>` put in by
         * a caller.
         */
        fun strings(key: String): List<String> = when (val value = entries[key]) {
            is Arr -> value.strings()
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }

        fun array(key: String): Arr = entries[key] as? Arr ?: Arr()

        fun has(key: String): Boolean = entries.containsKey(key)

        fun isEmpty(): Boolean = entries.isEmpty()

        fun toJson(): String = Json.write(this)
    }

    class Arr internal constructor(internal val items: MutableList<Any?>) {

        constructor() : this(ArrayList())

        fun add(value: String): Arr = apply { items.add(value) }

        fun add(value: Obj): Arr = apply { items.add(value) }

        fun add(value: Long): Arr = apply { items.add(value) }

        fun add(value: Int): Arr = apply { items.add(value) }

        fun add(value: Boolean): Arr = apply { items.add(value) }

        fun size(): Int = items.size

        fun isEmpty(): Boolean = items.isEmpty()

        fun objects(): List<Obj> = items.filterIsInstance<Obj>()

        fun strings(): List<String> = items.filterIsInstance<String>()

        fun toJson(): String = Json.write(this)
    }

    fun obj(): Obj = Obj()

    fun array(): Arr = Arr()

    fun obj(build: Obj.() -> Unit): Obj = Obj().apply(build)

    fun array(build: Arr.() -> Unit): Arr = Arr().apply(build)

    fun parseObject(raw: String?): Obj? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Parser(raw).parseObject() }.getOrNull()
    }

    fun parseArray(raw: String?): Arr? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Parser(raw).parseArray() }.getOrNull()
    }

    private fun write(value: Any?): String = buildString { writeValue(this, value) }

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long -> out.append(value.toString())
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                if (d.isFinite()) out.append(d.toString()) else out.append("0")
            }

            is Obj -> {
                out.append('{')
                var first = true
                for ((key, item) in value.entries) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeValue(out, item)
                }
                out.append('}')
            }

            is Arr -> {
                out.append('[')
                var first = true
                for (item in value.items) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, item)
                }
                out.append(']')
            }

            is List<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, item)
                }
                out.append(']')
            }

            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {

        private var index = 0

        fun parseObject(): Obj {
            skipWhitespace()
            return readObject()
        }

        fun parseArray(): Arr {
            skipWhitespace()
            return readArray()
        }

        private fun readValue(): Any? {
            skipWhitespace()
            if (index >= text.length) error("Unexpected end of JSON")
            return when (val ch = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else error("Unexpected char '$ch' at $index")
            }
        }

        private fun readObject(): Obj {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return Obj(map)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return Obj(map)
                    }

                    else -> error("Expected ',' or '}' at $index")
                }
            }
        }

        private fun readArray(): Arr {
            expect('[')
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return Arr(items)
            }
            while (true) {
                items.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return Arr(items)
                    }

                    else -> error("Expected ',' or ']' at $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                when (val ch = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) break
                        when (val escape = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 <= text.length) {
                                    val code = text.substring(index, index + 4).toIntOrNull(16)
                                    if (code != null) {
                                        out.append(code.toChar())
                                        index += 4
                                    }
                                }
                            }

                            else -> out.append(escape)
                        }
                    }

                    else -> out.append(ch)
                }
            }
            error("Unterminated string")
        }

        private fun readNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.' ||
                        text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')
            ) {
                index++
            }
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: 0L
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) error("Expected '$literal' at $index")
            index += literal.length
            return value
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            if (index >= text.length || text[index] != ch) {
                error("Expected '$ch' at $index")
            }
            index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
