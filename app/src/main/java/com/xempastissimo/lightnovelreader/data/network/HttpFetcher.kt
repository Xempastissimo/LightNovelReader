package com.xempastissimo.lightnovelreader.data.network

import com.xempastissimo.lightnovelreader.core.text.CharsetCodec
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/** One HTTP response, already decoded. */
data class HttpPage(
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

/** Raw response body, used for images and other binary payloads. */
data class HttpBytes(
    val finalUrl: String,
    val statusCode: Int,
    val bytes: ByteArray,
    val contentType: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpBytes) return false
        return finalUrl == other.finalUrl && statusCode == other.statusCode && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int =
        (finalUrl.hashCode() * 31 + statusCode) * 31 + bytes.contentHashCode()
}

/** Everything the HTTP layer can fail with, kept explicit so the UI can react. */
sealed class HttpFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Connection refused / DNS / TLS / timeout. */
    class Network(message: String, cause: Throwable? = null) : HttpFailure(message, cause)

    /** Non-2xx that is not a challenge and not an auth problem. */
    class Status(val statusCode: Int, message: String) : HttpFailure(message)

    /** The source refused the request (403/429) — stop, do not hammer. */
    class Blocked(val statusCode: Int, message: String) : HttpFailure(message)

    /** Cloudflare (or similar) interstitial; requires the user to solve it in a browser. */
    class Challenge(message: String) : HttpFailure(message)

    /** The source redirected to its login page: the session expired. */
    class AuthRequired(message: String) : HttpFailure(message)
}

/**
 * Thin `HttpURLConnection` wrapper.
 *
 * Responsibilities kept here (and nowhere else):
 * - GBK-safe form encoding and response decoding,
 * - a descriptive default header set for the book source,
 * - cookie send/store through [CookieStore],
 * - polite pacing plus retry/backoff through [RateLimiter].
 *
 * The interface exists so repositories can be tested against a fake client.
 */
interface HttpFetcher {
    suspend fun getText(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        charsetOverride: Charset? = null,
    ): String

    suspend fun getBytes(url: String, referer: String? = null): HttpBytes

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
        charset: Charset = Charset.forName(CharsetCodec.DEFAULT_SOURCE_CHARSET),
    ): String

    /**
     * Posts a form as a field list, where a name may appear more than once.
     *
     * HTML forms allow repetition and the bookshelf needs it: its bulk action sends
     * `checkid[]` once per ticked book, which a `Map` cannot express.
     */
    suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String? = null,
        charset: Charset = Charset.forName(CharsetCodec.DEFAULT_SOURCE_CHARSET),
    ): String

    suspend fun getPage(
        url: String,
        referer: String? = null,
        charsetOverride: Charset? = null,
    ): HttpPage
}

class HttpUrlConnectionFetcher(
    private val cookieStore: CookieStore,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) : HttpFetcher {

    override suspend fun getText(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        charsetOverride: Charset?,
    ): String = getPageInternal(url, referer, extraHeaders.ifEmpty { null }, charsetOverride, null).body

    override suspend fun getPage(url: String, referer: String?, charsetOverride: Charset?): HttpPage =
        getPageInternal(url, referer, null, charsetOverride, null)

    override suspend fun getBytes(url: String, referer: String?): HttpBytes =
        withContext(Dispatchers.IO) {
            val raw = execute(url, referer, null)
            HttpBytes(
                finalUrl = raw.finalUrl,
                statusCode = raw.statusCode,
                bytes = raw.body,
                contentType = raw.contentType,
            )
        }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String?,
        charset: Charset,
    ): String = postForm(url, form.entries.map { it.key to it.value }, referer, charset)

    override suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String?,
        charset: Charset,
    ): String {
        val body = fields.joinToString("&") { (key, value) ->
            "${encode(key, charset)}=${encode(value, charset)}"
        }.toByteArray(charset)
        return withContext(Dispatchers.IO) {
            decodeBody(execute(url, referer, body), null)
        }
    }

    private suspend fun getPageInternal(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>?,
        charsetOverride: Charset?,
        body: ByteArray?,
    ): HttpPage = withContext(Dispatchers.IO) {
        val raw = execute(url, referer, body, extraHeaders)
        HttpPage(
            url = raw.url,
            finalUrl = raw.finalUrl,
            statusCode = raw.statusCode,
            body = decodeBody(raw, charsetOverride),
            headers = raw.headers,
        )
    }

    private fun decodeBody(raw: RawResponse, charsetOverride: Charset?): String {
        if (!isTextual(raw.contentType)) return ""
        val charset = charsetOverride ?: CharsetCodec.charsetFromContentType(raw.contentType)
        val text = CharsetCodec.decode(raw.body, charset)
        guardAgainstChallenge(text)
        return text
    }

    private class RawResponse(
        val url: String,
        val finalUrl: String,
        val statusCode: Int,
        val body: ByteArray,
        val contentType: String?,
        val headers: Map<String, List<String>>,
    )

    private suspend fun execute(
        url: String,
        referer: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String>? = null,
    ): RawResponse {
        var attempt = 0
        while (true) {
            rateLimiter.acquire()
            val connection = try {
                openConnection(url, referer, body, extraHeaders)
            } catch (io: IOException) {
                throw HttpFailure.Network("无法连接书源：${io.message}", io)
            }

            try {
                val status = connection.responseCode
                if (status == 429 || status == 403) {
                    val diagnostics = buildString {
                        append("status=$status url=$url")
                        connection.headerFields?.entries
                            ?.filter { entry ->
                                val key = entry.key?.lowercase().orEmpty()
                                key == "server" || key == "cf-ray" || key == "cf-mitigated" ||
                                    key == "retry-after" || key == "x-frame-options"
                            }
                            ?.forEach { entry -> append(" ${entry.key}=${entry.value}") }
                    }
                    Log.w(TAG, diagnostics)
                    val retryAfterHeader = connection.headerFields?.entries
                        ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    connection.errorStream?.close()

                    if (status == 429) {
                        // The source's limit is per client, so pause everything, not
                        // just this request. Sibling requests would otherwise keep the
                        // limit tripped.
                        val retryAfter = RateLimiter.parseRetryAfter(retryAfterHeader)
                        rateLimiter.coolDown(retryAfter)
                        if (rateLimiter.shouldRetry(attempt, status)) {
                            attempt++
                            rateLimiter.backoff(attempt)
                            continue
                        }
                        throw HttpFailure.Blocked(
                            status,
                            "书源限流（429）：请求过于频繁，已暂停 " +
                                "${rateLimiter.cooldownRemainingMillis() / 1000} 秒后自动恢复",
                        )
                    }

                    if (rateLimiter.shouldRetry(attempt, status)) {
                        attempt++
                        rateLimiter.backoff(attempt)
                        continue
                    }
                    throw HttpFailure.Blocked(status, "书源拒绝访问（403），请降低请求频率")
                }
                if (status >= 500) {
                    connection.errorStream?.close()
                    if (rateLimiter.shouldRetry(attempt, status)) {
                        attempt++
                        rateLimiter.backoff(attempt)
                        continue
                    }
                    throw HttpFailure.Status(status, "书源服务器错误（$status）")
                }
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw HttpFailure.Status(status, "请求失败（$status）")
                }

                val headers = connection.headerFields ?: emptyMap()
                storeCookies(url, headers)

                val encoding = connection.contentEncoding
                val payload = connection.inputStream.use { stream ->
                    val source = if (encoding?.contains("gzip", ignoreCase = true) == true) {
                        GZIPInputStream(stream)
                    } else {
                        stream
                    }
                    source.readAllBytes()
                }
                val contentType = connection.contentType
                val headerMap = headers.entries
                    .filter { it.key != null }
                    .associate { it.key to (it.value ?: emptyList()) }

                return RawResponse(
                    url = url,
                    finalUrl = connection.url?.toString() ?: url,
                    statusCode = status,
                    body = payload,
                    contentType = contentType,
                    headers = headerMap,
                )
            } catch (timeout: SocketTimeoutException) {
                if (rateLimiter.shouldRetry(attempt, 504)) {
                    attempt++
                    rateLimiter.backoff(attempt)
                    continue
                }
                throw HttpFailure.Network("请求超时：${timeout.message}", timeout)
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun openConnection(
        url: String,
        referer: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String>?,
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            requestMethod = if (body == null) "GET" else "POST"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Accept-Encoding", "gzip, deflate")
            setRequestProperty("Upgrade-Insecure-Requests", "1")
        }
        referer?.let { connection.setRequestProperty("Referer", it) }
        extraHeaders?.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        cookieStore.headerFor(url)?.let { connection.setRequestProperty("Cookie", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=${CharsetCodec.DEFAULT_SOURCE_CHARSET}",
            )
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return connection
    }

    private fun storeCookies(url: String, headers: Map<String, List<String>>) {
        val setCookies = headers.entries
            .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value ?: emptyList() }
        if (setCookies.isNotEmpty()) cookieStore.save(url, setCookies)
    }

    private fun isTextual(contentType: String?): Boolean {
        if (contentType == null) return true
        val lower = contentType.lowercase()
        return lower.startsWith("text/") ||
            lower.contains("html") ||
            lower.contains("xml") ||
            lower.contains("json") ||
            lower.contains("javascript")
    }

    private fun guardAgainstChallenge(body: String) {
        if (body.length > CHALLENGE_SCAN_LIMIT) return
        val lower = body.lowercase()
        val looksLikeChallenge = (
            lower.contains("cf-browser-verification") ||
                lower.contains("cf_chl_opt") ||
                lower.contains("just a moment") ||
                lower.contains("checking your browser") ||
                lower.contains("enable javascript and cookies to continue")
            )
        if (looksLikeChallenge) {
            throw HttpFailure.Challenge("书源要求浏览器校验（Cloudflare），请先在浏览器中完成验证")
        }
        val isLoginPage = lower.contains("用户登录") &&
            lower.contains("password") &&
            lower.contains("login.php")
        if (isLoginPage && body.length < LOGIN_PAGE_SCAN_LIMIT) {
            throw HttpFailure.AuthRequired("登录状态已失效，请重新登录书源账号")
        }
    }

    private fun encode(value: String, charset: Charset): String =
        URLEncoder.encode(value, charset.name())

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"

        private const val CHALLENGE_SCAN_LIMIT = 200_000
        private const val LOGIN_PAGE_SCAN_LIMIT = 60_000
        private const val TAG = "HttpFetcher"
    }
}
/** Reads a stream fully; `InputStream.readAllBytes` needs API 33+ for some cases. */
private fun java.io.InputStream.readAllBytes(): ByteArray {
    val buffer = ByteArrayOutputStream(16 * 1024)
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
