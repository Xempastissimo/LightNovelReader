package com.xempastissimo.lightnovelreader.data.network

import android.content.Context
import android.util.Log
import com.xempastissimo.lightnovelreader.core.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Very small persistent cookie jar.
 *
 * `HttpURLConnection` ships with a `CookieManager`, but pulling it in drags
 * `android.webkit` into unit tests and its persistence is opaque. Book sources
 * need exactly three things: send matching cookies, absorb `Set-Cookie`, and
 * survive process death, so a JSON file is enough.
 */
class CookieStore(
    private val storageFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context) : this(File(context.filesDir, "session/cookies.json"))

    private class Cookie(
        val name: String,
        var value: String,
        var domain: String,
        var path: String,
        /** Epoch millis, or [SESSION_COOKIE_EXPIRY] for a cookie without an expiry attribute. */
        var expiresAt: Long,
        var hostOnly: Boolean,
    )

    private val lock = ReentrantLock()
    private val cookies = LinkedHashMap<String, Cookie>()

    init {
        lock.withLock { loadLocked() }
    }

    /** `Cookie:` header value for [url], or null when nothing matches. */
    fun headerFor(url: String): String? = lock.withLock {
        purgeExpiredLocked()
        val host = hostOf(url) ?: return@withLock null
        val path = pathOf(url)
        val selected = cookies.values.filter { cookie ->
            cookie.value.isNotEmpty() &&
                domainMatches(cookie, host) &&
                pathMatches(cookie.path, path)
        }
        if (selected.isEmpty()) null else selected.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** Applies every `Set-Cookie` header of a response. */
    fun save(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        lock.withLock {
            for (header in setCookieHeaders) applySetCookieLocked(url, header)
            persistLocked()
        }
    }

    /** Replaces the whole jar, e.g. when the user pastes a cookie blob. */
    /**
     * Replaces every cookie **for one domain**, leaving other domains untouched.
     *
     * Scoping matters: importing is done once per domain (`www.wenku8.net`,
     * `wenku8.net`, `www.wenku8.com`), and a jar-wide clear made the last import
     * wipe the session cookies imported from the first one.
     */
    fun replaceForDomain(raw: Map<String, String>, domain: String = DEFAULT_DOMAIN) {
        lock.withLock {
            val normalised = domain.removePrefix(".").lowercase()
            cookies.entries.removeAll { it.value.domain.equals(normalised, ignoreCase = true) }
            for ((name, value) in raw) {
                if (name.isBlank()) continue
                cookies[key(name, normalised, "/")] =
                    Cookie(name, value, normalised, "/", expiresAt = SESSION_COOKIE_EXPIRY, hostOnly = false)
            }
            persistLocked()
        }
    }

    /** Clears the whole jar. */
    fun replaceAll(raw: Map<String, String>, domain: String = DEFAULT_DOMAIN) {
        lock.withLock {
            cookies.clear()
            persistLocked()
        }
        replaceForDomain(raw, domain)
    }

    /**
     * Parses a raw `a=1; b=2` cookie header (as returned by `CookieManager`) and
     * merges it into the jar for [domain].
     *
     * The session cookies the site issues are domain-scoped and not `hostOnly`,
     * so a value read back for `wenku8.net` must stay reachable from
     * `www.wenku8.net` — hence [hostOnly] is false here.
     */
    fun importRawCookieHeader(raw: String, domain: String = DEFAULT_DOMAIN) {
        val pairs = LinkedHashMap<String, String>()
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            pairs[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
        }
        if (pairs.isNotEmpty()) replaceForDomain(pairs, domain)
    }

    fun snapshot(): Map<String, String> = lock.withLock {
        cookies.values.associate { it.name to it.value }
    }

    fun clear() {
        lock.withLock {
            cookies.clear()
            runCatching { storageFile.delete() }
        }
    }

    /** Value of a cookie by name (path/domain are ignored, first match wins). */
    fun valueOf(name: String): String? = lock.withLock {
        purgeExpiredLocked()
        cookies.values.firstOrNull { it.name == name && it.value.isNotEmpty() }?.value
    }

    fun remove(name: String) {
        lock.withLock {
            cookies.entries.removeAll { it.value.name == name }
            persistLocked()
        }
    }

    fun isLoggedIn(): Boolean = lock.withLock {
        cookies.values.any { it.name == USER_INFO_COOKIE && it.value.isNotEmpty() }
    }

    private fun applySetCookieLocked(url: String, header: String) {
        val parts = header.split(';')
        if (parts.isEmpty()) return
        val nameValue = parts[0]
        val eq = nameValue.indexOf('=')
        if (eq <= 0) return
        val name = nameValue.substring(0, eq).trim()
        val value = nameValue.substring(eq + 1).trim()
        if (name.isEmpty()) return

        val host = hostOf(url) ?: DEFAULT_DOMAIN
        var domain = host
        var hostOnly = true
        var path = defaultPath(url)
        var expiresAt = SESSION_COOKIE_EXPIRY
        var maxAge: Long? = null

        for (i in 1 until parts.size) {
            val part = parts[i].trim()
            val index = part.indexOf('=')
            val key = (if (index < 0) part else part.substring(0, index)).trim().lowercase()
            val attrValue = if (index < 0) "" else part.substring(index + 1).trim()
            when (key) {
                "domain" -> if (attrValue.isNotEmpty()) {
                    domain = attrValue.removePrefix(".").lowercase()
                    hostOnly = false
                }

                "path" -> if (attrValue.isNotEmpty()) path = attrValue
                "max-age" -> maxAge = attrValue.toLongOrNull()
                "expires" -> expiresAt = parseHttpDate(attrValue)
            }
        }

        val effectiveExpiry = when {
            maxAge != null && maxAge <= 0 -> EXPIRED
            maxAge != null -> clock() + maxAge * 1000L
            expiresAt >= 0 -> expiresAt
            else -> SESSION_COOKIE_EXPIRY
        }

        val storageKey = key(name, domain, path)
        if (effectiveExpiry == EXPIRED) {
            cookies.remove(storageKey)
        } else {
            cookies[storageKey] = Cookie(name, value, domain, path, effectiveExpiry, hostOnly)
        }
    }

    private fun purgeExpiredLocked() {
        val now = clock()
        val iterator = cookies.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val cookie = iterator.next().value
            if (cookie.expiresAt in 0 until now) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) persistLocked()
    }

    private fun domainMatches(cookie: Cookie, host: String): Boolean {
        if (cookie.hostOnly) return host.equals(cookie.domain, ignoreCase = true)
        return host.equals(cookie.domain, ignoreCase = true) ||
            host.endsWith("." + cookie.domain, ignoreCase = true)
    }

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean {
        if (requestPath == cookiePath) return true
        if (!requestPath.startsWith(cookiePath)) return false
        return cookiePath.endsWith("/") || requestPath.getOrNull(cookiePath.length) == '/'
    }

    private fun persistLocked() {
        try {
            persistInternal()
        } catch (t: Throwable) {
            // Persistence is best effort: losing the jar must not break a request.
            Log.w(TAG, "failed to persist the cookie jar", t)
        }
    }

    private fun persistInternal() {
        try {
            storageFile.parentFile?.mkdirs()
            val array = Json.array()
            for (cookie in cookies.values) {
                array.add(
                    Json.obj {
                        put("name", cookie.name)
                        put("domain", cookie.domain)
                        put("path", cookie.path)
                        put("value", cookie.value)
                        put("expiresAt", cookie.expiresAt)
                        put("hostOnly", cookie.hostOnly)
                    },
                )
            }
            val payload = array.toJson()
            val parent = storageFile.parentFile
            if (parent == null) {
                storageFile.writeText(payload)
            } else {
                parent.mkdirs()
                val tmp = File(parent, storageFile.name + ".tmp")
                tmp.writeText(payload)
                // Atomic-ish replace: `renameTo` refuses to overwrite on Windows.
                if (!tmp.renameTo(storageFile)) {
                    storageFile.delete()
                    if (!tmp.renameTo(storageFile)) {
                        storageFile.writeText(payload)
                        tmp.delete()
                    }
                }
            }
        } catch (t: Throwable) {
            // Persistence is best effort: a failure must never break a request.
            System.err.println("CookieStore: failed to persist session: ${t.message}")
        }
    }

    private fun loadLocked() {
        val text = runCatching { if (storageFile.exists()) storageFile.readText() else "" }
            .getOrDefault("")
        if (text.isBlank()) return
        val array = Json.parseArray(text) ?: return
        for (item in array.objects()) {
            val name = item.string("name")
            if (name.isEmpty()) continue
            val domain = item.string("domain", DEFAULT_DOMAIN)
            val path = item.string("path", "/")
            val stored = item.long("expiresAt", SESSION_COOKIE_EXPIRY)
            cookies[key(name, domain, path)] = Cookie(
                name = name,
                value = item.string("value"),
                domain = domain,
                path = path,
                expiresAt = if (stored == 0L) SESSION_COOKIE_EXPIRY else stored,
                hostOnly = item.boolean("hostOnly", true),
            )
        }
    }

    private fun key(name: String, domain: String, path: String) = "$name\u0000$domain\u0000$path"

    companion object {
        const val DEFAULT_DOMAIN = "www.wenku8.net"
        const val USER_INFO_COOKIE = "jieqiUserInfo"
        const val SESSION_COOKIE = "PHPSESSID"
        private const val TAG = "CookieStore"

        /** Sentinel for a cookie without an expiry attribute (lives until the app is killed). */
        const val SESSION_COOKIE_EXPIRY = -1L

        /** Sentinel used while applying `Set-Cookie`, meaning "delete this cookie". */
        private const val EXPIRED = -2L

        fun hostOf(url: String): String? {
            val schemeEnd = url.indexOf("://")
            val start = if (schemeEnd < 0) 0 else schemeEnd + 3
            var end = url.indexOf('/', start)
            if (end < 0) end = url.length
            val authority = url.substring(start, end)
            val host = authority.substringAfter('@').substringBefore(':')
            return host.ifEmpty { null }
        }

        fun pathOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            val start = if (schemeEnd < 0) 0 else schemeEnd + 3
            val slash = url.indexOf('/', start)
            if (slash < 0) return "/"
            val query = url.indexOf('?', slash)
            return if (query < 0) url.substring(slash) else url.substring(slash, query)
        }

        private fun defaultPath(url: String): String {
            val path = pathOf(url)
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else path.substring(0, lastSlash)
        }

        private val MONTHS = listOf(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
        )

        /** Parses RFC 1123 (`Wed, 09 Jun 2021 10:18:14 GMT`) plus the common broken variants. */
        fun parseHttpDate(raw: String): Long {
            if (raw.isBlank()) return 0L
            return runCatching {
                var cleaned = raw.replace(",", " ").trim().replace(Regex("\\s+"), " ")
                // RFC 1123 puts the abbreviated weekday first; RFC 850 spells it out.
                val weekday = cleaned.substringBefore(' ').lowercase().take(3)
                if (weekday in WEEKDAYS) cleaned = cleaned.substringAfter(' ', "")
                val tokens = cleaned.split(' ').filter { it.isNotEmpty() }
                if (tokens.size < 3) return 0L
                val day = tokens[0].toIntOrNull() ?: return 0L
                val month = MONTHS.indexOf(tokens[1].lowercase().take(3))
                if (month < 0) return 0L
                val year = tokens[2].toIntOrNull() ?: return 0L
                val time = tokens.getOrNull(3)?.split(':') ?: emptyList()
                val hour = time.getOrNull(0)?.toIntOrNull() ?: 0
                val minute = time.getOrNull(1)?.toIntOrNull() ?: 0
                val second = time.getOrNull(2)?.toIntOrNull() ?: 0
                val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"))
                calendar.clear()
                calendar.set(year, month, day, hour, minute, second)
                calendar.timeInMillis
            }.getOrDefault(0L)
        }

        private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    }
}
