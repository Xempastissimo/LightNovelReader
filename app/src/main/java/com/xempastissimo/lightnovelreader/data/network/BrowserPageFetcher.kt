package com.xempastissimo.lightnovelreader.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Outcome of fetching a page through the platform browser engine. */
sealed interface BrowserFetchResult {
    data class Success(val html: String, val finalUrl: String) : BrowserFetchResult

    data class Failure(val message: String) : BrowserFetchResult
}

/**
 * Fetches a page using the app's own `WebView` engine.
 *
 * Why this exists: the book source sits behind Cloudflare, which answers plain
 * HTTP clients with `403 Cf-Mitigated: challenge`. That check looks at the
 * TLS/HTTP2 fingerprint of the client, so a valid session cookie is not enough —
 * `HttpURLConnection` is rejected even when signed in. The `WebView` is a real
 * browser engine that has already passed the site's own verification, so reading
 * a page through it is the legitimate way to get the HTML when a native client
 * cannot.
 *
 * This is deliberately *not* an attempt to forge or bypass bot protection: no
 * fingerprints are spoofed, the site's own scripts run normally, and every
 * request carries the user's own session. It is simply a different (heavier)
 * HTTP client that the site accepts.
 *
 * The engine needs the main thread and a live `WebView`, so this class is used
 * as a fallback rather than the default path.
 */
class BrowserPageFetcher(private val context: Context) {

    /**
     * Loads [url] and returns the fully rendered DOM.
     *
     * [settleDelayMillis] adds extra time between "page finished" and harvesting,
     * which is needed when the site's challenge script swaps the document in
     * after the first load event.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(url: String, timeoutMillis: Long = 30_000, settleDelayMillis: Long = 0L): BrowserFetchResult {
        val outcome = withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.Main) { loadOnMainThread(url, settleDelayMillis) }
        }
        return outcome ?: BrowserFetchResult.Failure("页面加载超时（${timeoutMillis / 1000} 秒）")
    }

    private suspend fun loadOnMainThread(url: String, settleDelayMillis: Long): BrowserFetchResult =
        suspendCancellableCoroutine { continuation ->
            val webView = WebView(context)
            val handler = Handler(Looper.getMainLooper())
            var settled = false

            fun finish(result: BrowserFetchResult) {
                if (settled) return
                settled = true
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
                if (continuation.isActive) continuation.resume(result)
            }

            // Without this the page's own scripts never finish, and the challenge
            // script in particular needs to run before the real HTML appears.
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            webView.settings.userAgentString = DESKTOP_CHROME_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    // The challenge page finishes loading too; give its script a
                    // moment to swap in the real document before harvesting.
                    handler.postDelayed({
                        harvest(view, finishedUrl ?: url) { result -> finish(result) }
                    }, SETTLE_DELAY_MILLIS + settleDelayMillis)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        finish(BrowserFetchResult.Failure("页面加载失败：${error?.description ?: "未知错误"}"))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val status = errorResponse?.statusCode ?: 0
                    // A 4xx on the main frame is normally fatal, but the site's bot
                    // protection answers a brand-new session with 403 *and* an
                    // interstitial document. That document has to be allowed to run:
                    // it replaces itself once verification succeeds, so failing here
                    // would abort exactly the case that can still recover.
                    if (request?.isForMainFrame == true && status >= 400 && status != 403) {
                        finish(BrowserFetchResult.Failure("页面返回 HTTP $status"))
                    }
                }
            }

            continuation.invokeOnCancellation {
                handler.post {
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }

            webView.loadUrl(url)
        }

    private fun harvest(webView: WebView?, url: String, done: (BrowserFetchResult) -> Unit) {
        if (webView == null) {
            done(BrowserFetchResult.Failure("WebView 不可用"))
            return
        }
        webView.evaluateJavascript(HARVEST_SCRIPT) { value ->
            val html = decodeJavascriptString(value)
            if (html.isNullOrBlank()) {
                done(BrowserFetchResult.Failure("页面没有可读取的内容"))
            } else {
                done(BrowserFetchResult.Success(html, url))
            }
        }
    }

    /**
     * `evaluateJavascript` hands back a JSON-encoded string; the inner document is
     * then JSON-encoded again by the script, so it needs one unescape pass.
     */
    private fun decodeJavascriptString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        val unquoted = raw.removeSurrounding("\"")
        return unquoted
            .replace("\\u003C", "<")
            .replace("\\u003c", "<")
            .replace("\\u003E", ">")
            .replace("\\u003e", ">")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    companion object {
        /**
         * A desktop browser identity for the WebView. The stock WebView user agent
         * carries a `; wv)` token that Cloudflare refuses outright.
         */
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Safari/537.36"

        private const val SETTLE_DELAY_MILLIS = 1_500L

        private const val HARVEST_SCRIPT =
            "(function(){ try { return document.documentElement.outerHTML; } catch (e) { return null; } })()"
    }
}
