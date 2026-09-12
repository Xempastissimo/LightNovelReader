package com.xempastissimo.lightnovelreader.ui.screen.shelf

import com.xempastissimo.lightnovelreader.data.repo.DownloadedBook
import com.xempastissimo.lightnovelreader.data.repo.OfflineBook
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry

/**
 * Which rows a tab shows, in which order.
 *
 * Kept out of the view model and free of Android types so the four answers can be asserted
 * directly — the interesting part of each tab is *where its rows come from*, and that is a
 * question about lists, not about coroutines or file systems.
 */
internal fun shelfRows(
    tab: ShelfTab,
    entries: List<ShelfEntry>,
    offline: Map<Int, OfflineBook>,
    downloads: Map<Int, DownloadedBook>,
): List<ShelfEntry> = when (tab) {
    // Reading history, local and independent of the site's bookshelf.
    ShelfTab.RECENT -> entries.filter { it.progress != null }.sortedByDescending { it.lastReadAt }
    // Only what the site's bookshelf holds, in the order the site lists it.
    ShelfTab.SHELF -> entries.filter { it.online }.sortedByDescending { it.addedAt }
    // Whatever can be read with the network off, most recently read first. Not filtered
    // by `online`: a book does not have to be a favourite to be worth carrying.
    ShelfTab.CACHED -> cachedRows(entries, offline, downloads)
    ShelfTab.DOWNLOADED -> downloadedRows(entries, downloads)
}

/**
 * Rows for the 已缓存 tab, from the directory listing rather than from the shelf.
 *
 * A book with chapters on disk but no shelf row — a `shelf.json` that failed to parse
 * leaves exactly that — still gets a row under a placeholder name, because an offline
 * copy the user cannot see is one they cannot delete. The placeholder is enough to
 * open the book with; the detail screen fetches the real title.
 *
 * Books whose whole text was downloaded as a pack are left out here and belong to
 * 已下载 instead: the two tabs are meant to be different answers ("some chapters are on
 * this device" versus "this book's whole text is"), and listing a downloaded book in both
 * would make the download look like it added nothing.
 */
internal fun cachedRows(
    entries: List<ShelfEntry>,
    offline: Map<Int, OfflineBook>,
    downloads: Map<Int, DownloadedBook>,
): List<ShelfEntry> {
    if (offline.isEmpty()) return emptyList()
    val known = entries.filter { offline.containsKey(it.book.bookId) && it.book.bookId !in downloads }
    val knownIds = known.mapTo(HashSet()) { it.book.bookId }
    val orphans = offline.keys
        .filterNot { it in knownIds || it in downloads }
        .sorted()
        .map { bookId -> ShelfEntry(book = Book(bookId = bookId, title = "未知书籍 #$bookId")) }
    return known.sortedByDescending { it.lastReadAt } + orphans
}

/**
 * Rows for the 已下载 tab: the packs on this device, newest first.
 *
 * Built from the pack records rather than from the shelf, because a pack can outlive its
 * shelf row — removing a book from the site's bookshelf deliberately leaves the download
 * alone — and such a row still shows a real title, author and cover, since the record
 * stores the book's metadata next to the pack. Where a shelf row does exist it is used
 * instead, so the row keeps its reading progress.
 */
internal fun downloadedRows(
    entries: List<ShelfEntry>,
    downloads: Map<Int, DownloadedBook>,
): List<ShelfEntry> {
    if (downloads.isEmpty()) return emptyList()
    val known = entries.associateBy { it.book.bookId }
    return downloads.values
        .sortedByDescending { it.downloadedAt }
        .map { record -> known[record.bookId] ?: ShelfEntry(book = record.book) }
}
