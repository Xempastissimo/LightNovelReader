package com.xempastissimo.lightnovelreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xempastissimo.lightnovelreader.data.network.BrowserBackedFetcher
import com.xempastissimo.lightnovelreader.data.network.BrowserPageFetcher
import com.xempastissimo.lightnovelreader.data.network.BrowserFetchResult
import com.xempastissimo.lightnovelreader.data.network.CookieStore
import com.xempastissimo.lightnovelreader.data.network.HttpUrlConnectionFetcher
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Source
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Urls
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnoses and exercises the two transports side by side, on the device.
 *
 * The log lines are the point of this test:
 * - `PROBE native  …` reports what plain HTTP gets (expected: `403 Cf-Mitigated`),
 * - `PROBE browser …` reports what the app's browser engine gets (expected: HTML),
 * - `PROBE source  …` reports the real book: chapter count for `aid=3988`, the book
 *   whose 阅读 button links to `/novel/3/3988/index.htm`.
 *
 * The test always passes; it is a report. Nothing is bypassed — the browser engine
 * runs the site's own scripts with the user's own session.
 */
@RunWith(AndroidJUnit4::class)
class BrowserEngineProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportBothTransportsAndBookSource() = runBlocking {
        val browser = BrowserPageFetcher(context)

        val browserResult = runCatching { browser.fetch(Wenku8Urls.INDEX, timeoutMillis = 45_000) }
        val browserLine = browserResult.fold(
            onSuccess = { result ->
                when (result) {
                    is BrowserFetchResult.Success -> {
                        val html = result.html
                        val challenge = html.contains("challenge-platform") || html.contains("cf_chl_opt")
                        "PROBE browser ok chars=${html.length} signedIn=${html.contains("logout.php")} challenge=$challenge"
                    }

                    is BrowserFetchResult.Failure -> "PROBE browser failed message=${result.message}"
                }
            },
            onFailure = { "PROBE browser threw ${it.javaClass.simpleName}: ${it.message}" },
        )
        log(browserLine)

        val native = HttpUrlConnectionFetcher(CookieStore(File(context.filesDir, "session")))
        val nativeLine = runCatching { native.getText(Wenku8Urls.INDEX) }.fold(
            onSuccess = { "PROBE native ok chars=${it.length}" },
            onFailure = { "PROBE native failed ${it.javaClass.simpleName}: ${it.message}" },
        )
        log(nativeLine)

        // The real thing: the book source over the browser transport.
        val source = Wenku8Source(
            BrowserBackedFetcher(native, browser),
            CookieStore(File(context.filesDir, "session")),
        )
        val sourceLine = runCatching {
            val detail = source.detail(3988)
            "PROBE source ok title=${detail.book.title} prefix=${detail.novelPrefix} " +
                "volumes=${detail.volumes.size} chapters=${detail.chapters.size} " +
                "first=${detail.chapters.firstOrNull()?.title}"
        }.fold(
            onSuccess = { it },
            onFailure = { "PROBE source failed ${it.javaClass.simpleName}: ${it.message}" },
        )
        log(sourceLine)

        val reading = runCatching {
            val detail = source.detail(3988)
            val first = detail.chapters.first()
            val content = source.content(3988, first.chapterId, first.title)
            "PROBE reading ok chapter=${content.title} paragraphs=${content.paragraphs.size} " +
                "chars=${content.characterCount} requiresLogin=${content.requiresLogin}"
        }.fold(
            onSuccess = { it },
            onFailure = { "PROBE reading failed ${it.javaClass.simpleName}: ${it.message}" },
        )
        log(reading)
    }

    private fun log(line: String) {
        android.util.Log.i("BrowserEngineProbe", line)
        println(line)
    }
}
