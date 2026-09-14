package com.xempastissimo.lightnovelreader.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.xempastissimo.lightnovelreader.data.network.BrowserBackedFetcher
import com.xempastissimo.lightnovelreader.data.network.BrowserPageFetcher
import com.xempastissimo.lightnovelreader.data.network.CookieStore
import com.xempastissimo.lightnovelreader.data.network.HttpFetcher
import com.xempastissimo.lightnovelreader.data.network.HttpUrlConnectionFetcher
import com.xempastissimo.lightnovelreader.data.network.RateLimiter
import com.xempastissimo.lightnovelreader.data.network.RefreshThrottle
import com.xempastissimo.lightnovelreader.data.repo.BookRepository
import com.xempastissimo.lightnovelreader.data.repo.BookmarkStore
import com.xempastissimo.lightnovelreader.data.repo.ChapterCache
import com.xempastissimo.lightnovelreader.data.repo.ImageLoader
import com.xempastissimo.lightnovelreader.data.repo.PackStore
import com.xempastissimo.lightnovelreader.data.repo.SettingsRepository
import com.xempastissimo.lightnovelreader.data.repo.ShelfRepository
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.data.source.wenku8.Wenku8Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency-injection root.
 *
 * Everything is constructed lazily so that process start-up stays cheap: the
 * network stack is only built the first time a screen actually talks to a book
 * source. Swapping in a second source later means changing [bookSource] alone.
 */
class AppContainer(private val context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val cookieStore: CookieStore by lazy { CookieStore(context) }

    /** One pacer for the whole source: the site's limit is per client. */
    val rateLimiter: RateLimiter by lazy { RateLimiter() }

    /**
     * One limiter for every 刷新 button in the app.
     *
     * Shared on purpose: the screens are separate, but they all re-read the same
     * site on the same connection, so two of them tapped in quick succession are
     * still one client hitting the source twice within two seconds.
     */
    val refreshThrottle: RefreshThrottle by lazy { RefreshThrottle() }

    val httpFetcher: HttpFetcher by lazy {
        HttpUrlConnectionFetcher(
            cookieStore = cookieStore,
            rateLimiter = rateLimiter,
        )
    }

    /**
     * The fetch path used by the book source.
     *
     * Page reads go through the app's browser engine because the site rejects
     * plain HTTP clients (Cloudflare checks the TLS fingerprint). Form posts and
     * image downloads keep using the cheaper native client.
     */
    val sourceFetcher: BrowserBackedFetcher by lazy {
        BrowserBackedFetcher(native = httpFetcher, browser = browserFetcher, rateLimiter = rateLimiter)
    }

    val bookSource: BookSource by lazy { Wenku8Source(sourceFetcher, cookieStore) }

    /**
     * Browser-engine fetch path.
     *
     * The site rejects non-browser clients (Cloudflare checks the TLS fingerprint,
     * so a session cookie alone is not enough). This engine is only used as a
     * fallback and for diagnostics, because it needs the main thread.
     */
    val browserFetcher: BrowserPageFetcher by lazy { BrowserPageFetcher(context) }

    val chapterCache: ChapterCache by lazy { ChapterCache(context) }

    /**
     * The site's own whole-book packs, kept as downloaded.
     *
     * Separate from [chapterCache] on purpose: a pack is the artefact the source offered
     * (and can be re-read without another download), while the chapter cache is what the
     * reader consumes.
     */
    val packStore: PackStore by lazy { PackStore(context) }

    val bookRepository: BookRepository by lazy {
        BookRepository(bookSource, chapterCache, packStore, bookmarkStore)
    }

    val shelfRepository: ShelfRepository by lazy { ShelfRepository(context) }

    /**
     * Local bookmarks, next to `shelf.json` under `filesDir/library/`.
     *
     * Belongs to the repositories rather than to one screen: the reader writes them, the shelf
     * and the detail page count them, and `BookRepository` deletes them with a download.
     */
    val bookmarkStore: BookmarkStore by lazy { BookmarkStore(context) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val imageLoader: ImageLoader by lazy {
        ImageLoader(
            http = httpFetcher,
            cookieStore = cookieStore,
            cacheDir = File(context.filesDir, "image-cache"),
            refererProvider = { bookSource.imageReferer() },
        )
    }

    /** Reads the local library from disk; safe to call once per process. */
    fun warmUp() {
        appScope.launch {
            shelfRepository.load()
            packStore.load()
        }
    }
}

/** Ambient access to [AppContainer] for composables. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided. Wrap the UI in a CompositionLocalProvider.")
}
