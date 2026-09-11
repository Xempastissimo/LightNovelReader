package com.xempastissimo.lightnovelreader.data.network

import android.util.Log
import java.nio.charset.Charset

/**
 * Page fetcher that can read HTML through the platform browser engine.
 *
 * Background: the book source answers `HttpURLConnection` with
 * `403 Cf-Mitigated: challenge` — Cloudflare inspects the client's TLS/HTTP2
 * fingerprint, so a valid session cookie is not enough. The app's own `WebView`
 * does get through (verified on device: ~32 KB of HTML, signed in, no challenge
 * page). Every page this app needs is server-rendered HTML, so the browser engine
 * is a viable transport for pages.
 *
 * The engine is slower and needs the main thread plus a foreground process, so:
 * - form posts and binary downloads always use the lightweight native client;
 * - [preferBrowser] lets the app decide at runtime, and can be switched off in
 *   settings to fall back to plain HTTP.
 *
 * Nothing is spoofed or bypassed here: the site's own scripts run normally and
 * the user's own session is used.
 */
class BrowserBackedFetcher(
    private val native: HttpFetcher,
    private val browser: BrowserPageFetcher,
    /** Paces the browser loads too: the source's limit is per client, not per transport. */
    private val rateLimiter: RateLimiter = RateLimiter(),
    /** When true, pages are read through the browser engine. */
    var preferBrowser: Boolean = true,
) : HttpFetcher {

    override suspend fun getText(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        charsetOverride: Charset?,
    ): String = getPage(url, referer, charsetOverride).body

    override suspend fun getPage(url: String, referer: String?, charsetOverride: Charset?): HttpPage {
        if (!preferBrowser) {
            return native.getPage(url, referer, charsetOverride)
        }

        var html = loadViaBrowser(url, settleDelayMillis = 0L)
        if (isChallengePage(html)) {
            Log.i(TAG, "challenge page for $url, retrying after the script settles")
            html = loadViaBrowser(url, settleDelayMillis = RETRY_SETTLE_DELAY_MILLIS)
        }
        if (isChallengePage(html)) {
            // The interstitial only clears once the site's own script has verified
            // this browser session, which needs a real browsing session. Report the
            // way to establish one rather than returning the interstitial as content.
            throw HttpFailure.Challenge(
                "站点要求先完成一次浏览器校验。请到「设置 → 账号 → 使用浏览器登录」，" +
                    "在打开的页面里完成校验后返回，再重试。",
            )
        }
        if (html.isBlank()) {
            throw HttpFailure.Status(200, "浏览器内核没有返回页面内容")
        }
        return HttpPage(url = url, finalUrl = url, statusCode = 200, body = html, headers = emptyMap())
    }

    private suspend fun loadViaBrowser(url: String, settleDelayMillis: Long): String {
        rateLimiter.acquire()
        return when (val result = browser.fetch(url, settleDelayMillis = settleDelayMillis)) {
            is BrowserFetchResult.Success -> result.html
            is BrowserFetchResult.Failure -> throw HttpFailure.Network(result.message)
        }
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String?,
        charset: Charset,
    ): String = native.postForm(url, form, referer, charset)

    override suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String?,
        charset: Charset,
    ): String = native.postForm(url, fields, referer, charset)

    override suspend fun getBytes(url: String, referer: String?): HttpBytes = native.getBytes(url, referer)

    companion object {
        private const val TAG = "BrowserBackedFetcher"
        private const val RETRY_SETTLE_DELAY_MILLIS = 4_000L

        /**
         * True when the document is Cloudflare's interstitial rather than content.
         *
         * The title is the reliable signal: the interstitial is a full document
         * (tens of KB, so size heuristics miss it) and titles itself
         * "Just a moment..." / "Attention Required!".
         */
        fun isChallengePage(html: String): Boolean {
            val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
                ?.groupValues?.get(1)
                ?.lowercase()
                .orEmpty()
            if (title.contains("just a moment") ||
                title.contains("attention required") ||
                title.contains("checking your browser")
            ) {
                return true
            }
            val head = html.take(CHALLENGE_SCAN_LIMIT).lowercase()
            return head.contains("cf_chl_opt") ||
                head.contains("challenge-platform") ||
                head.contains("cf-browser-verification") ||
                head.contains("enable javascript and cookies to continue")
        }

        private const val CHALLENGE_SCAN_LIMIT = 20_000
    }
}
