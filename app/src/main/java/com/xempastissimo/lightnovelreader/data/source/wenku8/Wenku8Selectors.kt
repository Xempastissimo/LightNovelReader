package com.xempastissimo.lightnovelreader.data.source.wenku8

/**
 * Selector map for the source, isolated in one file so a markup change touches
 * nothing else.
 *
 * Every entry is an ordered candidate list — the parser takes the first match —
 * so a partial redesign on the source degrades gracefully instead of throwing.
 * Lists are split into "primary" (verified against the live site) and fallbacks.
 */
object Wenku8Selectors {

    // ---------------------------------------------------------------- detail page

    /** Container of the whole book detail block. */
    val DETAIL_ROOT = listOf("div#content")

    /**
     * The metadata table inside [DETAIL_ROOT]: the one whose first row contains
     * both the cover image and a chapter link. Located by scanning tables rather
     * than with a CSS selector, because the source uses no classes here and the
     * project's selector subset has no `:has()`.
     */
    val DETAIL_TABLE_HINT_IMAGE = "img"
    val DETAIL_TABLE_HINT_LINK = "a[href*=novel]"

    /** Cover image element on the detail page. */
    val DETAIL_COVER = listOf("img[src*=image]", "td img")

    /**
     * Heading cell holding the book name plus action labels.
     *
     * Only used as a fallback for the title (the `<title>` tag is preferred) and
     * never read wholesale, because on the live page it also contains
     * "[推一下!]" and "举报/报错".
     */
    val DETAIL_TITLE_CELL = listOf("div#content table tr td", "div#content div")

    /** Candidate cells/labels holding a single metadata value; matched by label text. */
    val META_AUTHOR_LABEL = "小说作者"
    val META_CATEGORY_LABEL = "文库分类"
    val META_STATUS_LABEL = "文章状态"
    val META_UPDATE_LABEL = "最后更新"

    /** `作品Tags：a b c`. */
    val DETAIL_TAGS = listOf("span.hottext b")

    /** The `内容简介：` label followed by the intro text. */
    val DETAIL_INTRO_LABEL = "内容简介"

    /**
     * The 「阅读」 fieldset's 「小说目录」 link on the detail page:
     * `/novel/{cat}/{aid}/index.htm`.
     *
     * This is the canonical source of a book's category segment — it exists even
     * for books that have no chapter links on the detail page yet.
     */
    val DETAIL_CATALOG_LINK = listOf(
        "a[href*=/novel/][href$=index.htm]",
        "a[href$=index.htm]",
    )

    /** Any detail-page link that reveals the `/novel/{cat}/{aid}/` prefix. */
    val DETAIL_FIRST_CHAPTER_LINK = listOf("a[href*=novel]")

    // ------------------------------------------------------------- chapter list page

    /**
     * Single table holding all volumes and chapters.
     *
     * `table.css` is matched **without** requiring a `#content` ancestor: on the
     * live catalogue page the table is a direct child of `<body>`, not inside
     * `div#content`. Requiring that ancestor is what made every book report zero
     * chapters even though the page had been fetched successfully.
     *
     * Chapter cells use `td.ccss`, volume separators `td.vcss`; both were verified
     * against the live page.
     */
    val TOC_TABLE = listOf(
        "table.css",
        "div#content table.css",
        "table[cellpadding][cellspacing]",
        "div#content table",
        "table",
    )

    /** Fallback: locate the table that actually carries chapter cells. */
    val TOC_ANY_CHAPTER_LINK = "td.ccss a[href]"

    /** Volume separator cell. */
    val TOC_VOLUME_CELL = "td.vcss"

    /** Chapter cell (contains the anchor). */
    val TOC_CHAPTER_CELL = "td.ccss"

    val TOC_CHAPTER_LINK = "a[href]"

    // ------------------------------------------------------------- chapter body page

    val CONTENT_ROOT = listOf("div#content")

    /** Breadcrumb / navigation lists that must not be treated as story text. */
    val CONTENT_NAV_LIST = "ul#contentdp"

    /** Text separator used by the source between paragraphs. */
    const val CONTENT_LINE_BREAK = "br"

    // --------------------------------------------------------------- list pages

    /**
     * Ranking pages render one `table.grid` per page whose single cell holds the
     * entries, each entry being a `div` with a cover image, a `/book/{aid}.htm`
     * link, an 加入书架 link and a "作者:…" line.
     */
    val LIST_TABLE = listOf("div#content table.grid", "table.grid", "div#content table")

    /** Book anchor; the entry's own container is found by walking up from it. */
    val LIST_BOOK_LINK = "a[href*=/book/]"

    val LIST_ONLINE_SHELF_ADD = "a[href*=addbookcase]"

    /** "作者:xyz" / "状态:连载中" trailing text inside a ranking entry. */
    val LIST_AUTHOR = Regex("""作者[:：]\s*([^\s/]+)""")

    // ------------------------------------------------------------------ bookcase

    /** `/modules/article/readbookcase.php?aid={aid}&bid={bid}[&cid={cid}]`. */
    val BOOKCASE_ENTRY = "a[href*=readbookcase]"
    val BOOKCASE_AUTHOR_LINK = "a[href*=authorarticle]"

    /**
     * The header cell: `您的书架可收藏 300 本，已收藏 3 本，本组有 3 本。`
     *
     * This is the only place the real totals appear — the table below it is paged and
     * grouped, so its length is never the account's total.
     */
    const val BOOKCASE_SUMMARY = "div.gridtop"

    /**
     * The row checkbox, `name="checkid[]"`.
     *
     * Its `value` is the row's own id on the bookshelf, which is **not** the book id this
     * app uses everywhere else (see [Wenku8Urls.removeFromBookcase]).
     */
    const val BOOKCASE_ROW_CHECKBOX_NAME = "checkid"

    /**
     * The bulk-action dropdown in the page footer: `选中项目 [下拉框] 确认`.
     *
     * Worth stating plainly, because guessing here cost two rounds: the bookshelf has no
     * per-row delete *link*. The 移除 control is a `javascript:` `document.location`
     * call, and the footer is a real `<form>` — so removal has to be driven from the ids
     * read off this page, never from a constructed link.
     */
    const val BOOKCASE_ACTION_SELECT_NAME = "newclassid"

    /** `newclassid` value meaning "move the ticked books off the bookshelf". */
    const val BOOKCASE_CLASS_REMOVE = "-1"

    /** `aid=(\d+)` inside a bookcase link. */
    val BOOKCASE_AID = Regex("""[?&]aid=(\d+)""")

    /** `cid=(\d+)` inside a bookcase link (present on the 最新章节 link). */
    val BOOKCASE_CID = Regex("""[?&]cid=(\d+)""")

    // ------------------------------------------------------------------- homepage

    /** "最近更新" rows: `<li>` pairs of (entry, author + date). */
    val HOME_RECENT_ITEM = "li"
    val RECENT_AUTHOR_DATE = Regex("""^(.+?)\s*\((\d{1,2})-(\d{1,2})\)$""")

    /**
     * Cover image inside a 最近更新 row, when the listing carries one.
     *
     * The live rows hold no `<img>` at all, so the parser derives the path from
     * the latest-chapter URL instead; this selector only makes sure a cover the
     * source adds later is used as-is rather than replaced by the derivation.
     */
    val HOME_RECENT_COVER = "img[src*=image]"

    /**
     * Homepage "最近更新" entries. The element's combined text puts the anchor
     * label (the truncated title) between the 《》 marks, so the pattern allows
     * optional whitespace there: `[文库] 《title》 latest`.
     */
    val RECENT_ENTRY = Regex("""^\[(?<category>[^\]]+)]\s*《\s*(?<title>.*?)\s*》(?<latest>.*)$""")

    /** `作者: xxx - 轻小说文库` style `<title>`. */
    val DETAIL_TITLE = Regex("""^(?<title>.+?) - (?<author>.+?) - (?<category>.+?) - """)
}
