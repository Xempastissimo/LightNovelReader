package com.xempastissimo.lightnovelreader.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Byte-to-string conversion for book source pages.
 *
 * The first book source serves GBK pages with an inconsistent `Content-Type`
 * header, so decoding always goes through a probe chain:
 * declared charset -> meta charset in the payload -> UTF-8 -> GBK.
 */
object CharsetCodec {

    const val DEFAULT_SOURCE_CHARSET = "GBK"

    private val UTF8 = Charsets.UTF_8

    fun charsetFor(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        val normalized = name.trim().trim('"', '\'').lowercase()
        val candidate = when (normalized) {
            "gb2312", "gb_2312", "gbk", "cp936", "ms936", "x-gbk" -> "GBK"
            "gb18030" -> "GB18030"
            "big5", "big-5", "cp950" -> "Big5"
            "utf8", "utf-8" -> "UTF-8"
            "iso-8859-1", "latin1" -> "ISO-8859-1"
            else -> normalized
        }
        return runCatching { Charset.forName(candidate) }.getOrNull()
    }

    /** Extracts `charset=` from a Content-Type header value. */
    fun charsetFromContentType(contentType: String?): Charset? {
        if (contentType.isNullOrBlank()) return null
        val marker = contentType.lowercase().indexOf("charset=")
        if (marker < 0) return null
        val raw = contentType.substring(marker + "charset=".length).trim().trimEnd(';')
        return charsetFor(raw)
    }

    /** Extracts the charset declared inside an HTML document. */
    fun charsetFromHtml(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val patterns = listOf(
            Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(head) ?: continue
            charsetFor(match.groupValues[1])?.let { return it }
        }
        return null
    }

    /**
     * Decodes [bytes] using [declared], falling back to the document meta tag and
     * finally to the source default. Malformed input never throws.
     */
    fun decode(bytes: ByteArray, declared: Charset? = null): String {
        if (bytes.isEmpty()) return ""
        declared?.let { charset ->
            decodeStrict(bytes, charset)?.let { return it }
        }
        charsetFromHtml(bytes)?.let { charset ->
            if (charset != declared) decodeStrict(bytes, charset)?.let { return it }
        }
        decodeStrict(bytes, UTF8)?.let { return it }
        charsetFor(DEFAULT_SOURCE_CHARSET)?.let { charset ->
            decodeStrict(bytes, charset)?.let { return it }
        }
        return String(bytes, UTF8)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
