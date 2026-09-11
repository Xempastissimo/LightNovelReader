package com.xempastissimo.lightnovelreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xempastissimo.lightnovelreader.data.network.BrowserBackedFetcher
import com.xempastissimo.lightnovelreader.data.network.BrowserPageFetcher
import com.xempastissimo.lightnovelreader.data.network.CookieStore
import com.xempastissimo.lightnovelreader.data.network.HttpUrlConnectionFetcher
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Parser
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Source
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Urls
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reports, from the device, exactly what the book source returns for real books.
 *
 * The log lines are the point:
 * - `DETAIL <aid> …` shows the parsed metadata, catalogue prefix and chapter count,
 * - `DETAIL <aid> FAILED …` shows the concrete failure when a book cannot be opened,
 * - `TOC <aid> …` shows what the catalogue page itself looked like, which
 *   distinguishes "page not fetched" from "page fetched but not parsed".
 *
 * The test always passes; it is a diagnostic report. It reads only metadata
 * (titles, chapter names), never chapter text.
 */
@RunWith(AndroidJUnit4::class)
class BookDetailProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportBookDetails() = runBlocking {
        val cookies = CookieStore(File(context.filesDir, "session"))
        val browser = BrowserPageFetcher(context)
        val fetcher = BrowserBackedFetcher(HttpUrlConnectionFetcher(cookies), browser)
        val source = Wenku8Source(fetcher, cookies)

        log("signedIn=${source.isLoggedIn()} session=${source.currentSession()?.userName}")

        for (aid in listOf(3988, 2536, 1973)) {
            var prefix = ""
            val line = runCatching {
                val detail = source.detail(aid)
                prefix = detail.novelPrefix
                "DETAIL $aid ok title=${detail.book.title} prefix=${detail.novelPrefix} " +
                    "volumes=${detail.volumes.size} chapters=${detail.chapters.size} " +
                    "introChars=${detail.intro.length} cover=${detail.book.coverUrl != null}"
            }.fold({ it }, { "DETAIL $aid FAILED ${it.javaClass.simpleName}: ${it.message}" })
            log(line)

            // Inspect the catalogue page the source actually uses (the prefix comes
            // from the detail page), not a guessed URL.
            val tocUrl = if (prefix.isNotBlank()) Wenku8Urls.chapterList(aid, prefix) else null
            if (tocUrl != null) {
                val raw = runCatching {
                    val html = fetcher.getText(tocUrl)
                    val parsed = com.xempastissimo.lightnovelreader.core.html.Html.parse(html)
                    // Parse the very same bytes with the real parser, so a fetch
                    // problem and a parsing problem cannot be confused.
                    val volumes = Wenku8Parser.parseChapterList(html, aid)
                    val firstRowCells = parsed.select("table.css tr").firstOrNull()
                        ?.select("td")?.map { it.tag + "." + it.classes() }
                    "TOC $aid url=$tocUrl chars=${html.length} title=${Wenku8Parser.pageTitle(html)} " +
                        "challenge=${BrowserBackedFetcher.isChallengePage(html)} " +
                        "loginPage=${Wenku8Parser.looksLikeLoginPage(html)} " +
                        "tables=${parsed.select("table").size} css=${parsed.select("table.css").size} " +
                        "vcss=${parsed.select("td.vcss").size} ccss=${parsed.select("td.ccss").size} " +
                        "parsedVolumes=${volumes.size} parsedChapters=${volumes.sumOf { it.chapters.size }} " +
                        "firstRowCells=$firstRowCells"
                }.fold({ it }, { "TOC $aid FAILED ${it.javaClass.simpleName}: ${it.message}" })
                log(raw)
            }
        }
    }

    private fun log(line: String) {
        android.util.Log.i("BookDetailProbe", line)
        println(line)
    }
}
