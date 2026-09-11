package com.xempastissimo.lightnovelreader.domain.model

/** A book as it appears in a list (rankings, catalog, search results, bookshelf). */
data class Book(
    val bookId: Int,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val category: String = "",
    val status: String = "",
    val latestChapter: String = "",
    val updatedAt: String = "",
    /** Anchor text of the entry when the source truncates long titles. */
    val shortTitle: String = title,
) {
    val key: String get() = bookId.toString()
}

data class Chapter(
    val chapterId: Int,
    val title: String,
    val volumeId: Int,
    val volumeTitle: String,
    val index: Int,
) {
    val key: String get() = chapterId.toString()
}

data class Volume(
    val volumeId: Int,
    val title: String,
    val chapters: List<Chapter>,
)

/** Everything the detail screen needs. */
data class BookDetail(
    val book: Book,
    val intro: String = "",
    val tags: List<String> = emptyList(),
    val volumes: List<Volume> = emptyList(),
    /** Path prefix of this book's chapters, e.g. `/novel/1/1973/`. */
    val novelPrefix: String = "",
    val downloadLinks: List<Pair<String, String>> = emptyList(),
) {
    val chapters: List<Chapter> get() = volumes.flatMap { it.chapters }

    val chapterCount: Int get() = chapters.size

    fun chapterIndex(chapterId: Int): Int = chapters.indexOfFirst { it.chapterId == chapterId }
}

/** A block inside chapter content: either text or an illustration. */
sealed interface ContentBlock {
    data class Paragraph(val text: String) : ContentBlock

    data class Illustration(val url: String) : ContentBlock
}

data class ChapterContent(
    val bookId: Int,
    val chapterId: Int,
    val title: String,
    val volumeTitle: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val previousChapterId: Int? = null,
    val nextChapterId: Int? = null,
    /** `true` when the source demanded a login before serving the body. */
    val requiresLogin: Boolean = false,
) {
    val paragraphs: List<String>
        get() = blocks.filterIsInstance<ContentBlock.Paragraph>().map { it.text }

    val illustrations: List<String>
        get() = blocks.filterIsInstance<ContentBlock.Illustration>().map { it.url }

    /** Cheap size estimate used by the cache and download progress. */
    val characterCount: Int get() = paragraphs.sumOf { it.length }
}

/** Where the user stopped reading a given book. */
data class ReadingProgress(
    val bookId: Int,
    val chapterId: Int,
    val chapterIndex: Int,
    val paragraphIndex: Int = 0,
    val percentInChapter: Float = 0f,
    val updatedAt: Long = 0L,
)

/** A book the user keeps locally, with just enough metadata for the shelf list. */
data class ShelfEntry(
    val book: Book,
    val addedAt: Long = 0L,
    val progress: ReadingProgress? = null,
    val cachedChapterIds: Set<Int> = emptySet(),
    /** `true` when the entry mirrors the source's own bookshelf. */
    val online: Boolean = false,
) {
    val lastReadAt: Long get() = progress?.updatedAt ?: addedAt

    val hasOfflineCopy: Boolean get() = cachedChapterIds.isNotEmpty()
}

/** Sort keys accepted by the ranking endpoints. */
enum class RankType(val sort: String) {
    ALL_VISIT("allvisit"),
    MONTH_VISIT("monthvisit"),
    WEEK_VISIT("weekvisit"),
    DAY_VISIT("dayvisit"),
    GOOD_NUM("goodnum"),
    POST_DATE("postdate"),
    LAST_UPDATE("lastupdate"),
    ANIME("anime"),
    FULL_FLAG("fullflag"),
}

/** Search field selector on the source's search form. */
enum class SearchField(val value: String) {
    TITLE("articlename"),
    AUTHOR("author"),
}

/** Logged-in user, decoded from the source's session cookie. */
data class UserSession(
    val userId: String = "",
    val userName: String = "",
    val groupName: String = "",
    val honorName: String = "",
    val loggedInAt: Long = 0L,
) {
    val isLoggedIn: Boolean get() = userName.isNotEmpty()
}
