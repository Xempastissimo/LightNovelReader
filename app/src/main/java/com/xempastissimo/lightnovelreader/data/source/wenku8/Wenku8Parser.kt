package com.xempastissimo.lightnovelreader.data.source.wenku8

import com.xempastissimo.lightnovelreader.core.html.Element
import com.xempastissimo.lightnovelreader.core.html.Html
import com.xempastissimo.lightnovelreader.core.text.TextCleaner
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.BookDetail
import com.xempastissimo.lightnovelreader.domain.model.Chapter
import com.xempastissimo.lightnovelreader.domain.model.ChapterContent
import com.xempastissimo.lightnovelreader.domain.model.ContentBlock
import com.xempastissimo.lightnovelreader.domain.model.Volume
import com.xempastissimo.lightnovelreader.domain.model.RankType

/**
 * HTML -> domain model translation for the source.
 *
 * All page-shape knowledge lives behind [Wenku8Selectors] and [Wenku8Urls]; this
 * class only walks the tree and normalises values, so it stays unit-testable
 * against captured markup fragments.
 */
object Wenku8Parser {

    /**
     * The bookshelf page's bulk-action form, as the page itself declares it.
     *
     * Every name here is read from the markup rather than hard-coded, so a rename on the
     * site turns into "this source cannot remove books" instead of a request that looks
     * fine and silently does nothing.
     */
    data class BookcaseActionForm(
        val action: String,
        /** The repeated field carrying the ticked rows' ids (`checkid[]`). */
        val selectionField: String,
        /** The dropdown naming the operation (`newclassid`). */
        val actionField: String,
        /** The form's own hidden fields, including the group it belongs to. */
        val hidden: Map<String, String>,
        val submitField: String?,
        val submitValue: String,
    )

    /** The counts the bookshelf page states about itself. */
    data class BookcaseSummary(val capacity: Int, val total: Int, val inGroup: Int)

    private val BOOKCASE_COUNTS = Regex(
        """可收藏\s*(\d+)\s*本[，,]\s*已收藏\s*(\d+)\s*本[，,]\s*本组有\s*(\d+)\s*本""",
    )

    // ------------------------------------------------------------------ detail page

    fun parseBookDetail(html: String, bookId: Int): BookDetail? {
        val root = Html.parse(html)
        val content = firstMatch(root, Wenku8Selectors.DETAIL_ROOT) ?: root

        val cover = firstMatch(content, Wenku8Selectors.DETAIL_COVER)
        val coverUrl = Wenku8Urls.coverFromPage(cover?.attr("src"))

        val pageTitle = root.selectFirst("title")?.text().orEmpty()
        val parsedTitle = parseTitleTag(pageTitle)

        // The `<title>` tag is `书名 - 作者 - 文库 - 轻小说文库` and is the cleanest
        // source for the title. The heading cell is only a fallback: it carries
        // extra button labels ("[推一下!]", "举报/报错") that must not leak into the
        // book's name.
        val headingCell = firstMatch(content, Wenku8Selectors.DETAIL_TITLE_CELL)?.text().orEmpty()
        val title = parsedTitle.title.ifBlank { cleanBookTitle(headingCell) }

        // Metadata is spread over sibling tables/divs inside #content, so the
        // collectors scan the whole block rather than one chosen element.
        val author = labeledText(content, Wenku8Selectors.META_AUTHOR_LABEL)
            .ifBlank { parsedTitle.author }
        val category = labeledText(content, Wenku8Selectors.META_CATEGORY_LABEL)
            .ifBlank { parsedTitle.category }
        val status = labeledText(content, Wenku8Selectors.META_STATUS_LABEL)
        val updatedAt = labeledText(content, Wenku8Selectors.META_UPDATE_LABEL)

        val tags = collectTags(content)
        val intro = collectIntro(content)

        // The category segment only appears in `/novel/{cat}/{aid}/...` URLs. The
        // 阅读 fieldset's 「小说目录」 link (`…/index.htm`) is the canonical one;
        // a chapter link is accepted as a fallback.
        val catalogHref = Wenku8Selectors.DETAIL_CATALOG_LINK
            .asSequence()
            .flatMap { selector -> content.select(selector).asSequence() }
            .mapNotNull { it.attr("href") }
            .firstOrNull { Wenku8Urls.novelPrefixOf(it) != null }

        val firstChapterHref = content
            .select(Wenku8Selectors.DETAIL_FIRST_CHAPTER_LINK.joinToString(","))
            .mapNotNull { it.attr("href") }
            .firstOrNull { Wenku8Urls.novelPrefixOf(it) != null }

        val novelPrefix = Wenku8Urls.novelPrefixOf(catalogHref ?: firstChapterHref)

        if (title.isBlank() && novelPrefix == null) return null

        val book = Book(
            bookId = bookId,
            title = title,
            author = author,
            // When the page has no cover image the path is still derivable from
            // the category segment of the catalogue/first-chapter URL, e.g.
            // `/novel/3/3988/index.htm` + aid 3988 -> `…/image/3/3988/3988s.jpg`.
            coverUrl = coverUrl ?: Wenku8Urls.coverFromNovelUrl(bookId, catalogHref ?: firstChapterHref),
            category = category,
            status = status,
            latestChapter = collectLatestChapter(content),
            updatedAt = updatedAt,
        )

        return BookDetail(
            book = book,
            intro = intro,
            tags = tags,
            volumes = emptyList(),
            novelPrefix = novelPrefix.orEmpty(),
            downloadLinks = collectDownloadLinks(content),
        )
    }

    /**
     * The detail table is identified structurally: its first row contains the
     * cover image and the link used to reach the chapter list.
     */
    private fun findDetailTable(content: Element): Element? {
        val tables = content.select("table")
        return tables.firstOrNull { table ->
            table.selectFirst(Wenku8Selectors.DETAIL_TABLE_HINT_IMAGE) != null &&
                table.selectFirst(Wenku8Selectors.DETAIL_TABLE_HINT_LINK) != null
        } ?: run {
            val covers = Wenku8Selectors.DETAIL_COVER
            tables.firstOrNull { table -> covers.any { table.selectFirst(it) != null } }
        }
    }

    /** Segments of a page title: `书名 - 作者 - 文库 - 轻小说文库`. */
    private class TitleParts(val title: String, val author: String, val category: String)

    /**
     * Splits the page title into its parts.
     *
     * The site uses a different number of segments on different page types, so the
     * parts are taken positionally:
     * - detail page: `书名 - 作者 - 文库 - 轻小说文库`
     * - catalogue page: `书名…-作者-文库-轻小说文库`
     * - and a trailing `小说在线阅读与TXT电子书下载` on some pages.
     *
     * A fixed regex demanding all three separators silently produced an empty
     * title, so this is deliberately tolerant.
     */
    private fun parseTitleTag(pageTitle: String): TitleParts {
        var cleaned = pageTitle.trim()
        cleaned = cleaned.substringBefore("小说在线阅读")
        cleaned = cleaned.replace(Regex("""\s*-\s*轻小说文库\s*$"""), "")
        cleaned = cleaned.replace(Regex("""\s*-\s*最新最全的.*$"""), "")

        val segments = cleaned.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            segments.isEmpty() -> TitleParts("", "", "")
            else -> TitleParts(
                title = segments.getOrNull(0).orEmpty(),
                author = segments.getOrNull(1).orEmpty(),
                category = segments.getOrNull(2).orEmpty(),
            )
        }
    }

    /**
     * `《书名》[推一下!]` -> `书名`, and strips the trailing action labels the
     * heading cell carries on the live page.
     */
    private fun cleanBookTitle(raw: String): String {
        val oneLine = TextCleaner.oneLine(raw)
        val afterBookMark = oneLine.substringAfter('《', "").substringBefore('》', "")
        if (afterBookMark.isNotBlank()) return afterBookMark.trim()
        return oneLine
            .replace(Regex("""\[[^\]]*]"""), "")
            .replace(Regex("""(推一下|举报|报错|加入书架|推荐本书|小说目录)"""), "")
            .replace("/", "")
            .trim()
    }

    /** Finds `标签：值` inside a cell and returns the value. */
    private fun labeledText(scope: Element, label: String): String {
        val cell = scope.select("td").firstOrNull { it.text().contains(label) } ?: return ""
        val raw = TextCleaner.oneLine(cell.text())
        val index = raw.indexOf(label)
        if (index < 0) return ""
        return raw.substring(index + label.length).trimStart('：', ':', ' ', '\u00a0').trim()
    }

    private fun collectTags(scope: Element): List<String> {
        val candidates = scope.select(Wenku8Selectors.DETAIL_TAGS.joinToString(","))
        for (element in candidates) {
            val text = TextCleaner.oneLine(element.text())
            val index = text.indexOf("作品Tags")
            if (index < 0) continue
            val value = text.substring(index + "作品Tags".length).trimStart('：', ':', ' ')
            val tags = value.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) return tags
        }
        return emptyList()
    }

    /**
     * Reads the value that follows a `内容简介：` label.
     *
     * Three shapes exist on the source pages: the body is inline after the label
     * (`内容简介：正文`), the body is the next sibling block, or the label is a
     * spacer and the body is a bare text node in the label's parent. The last
     * case is resolved by slicing the parent's own text at the label and stopping
     * at the next known label.
     */
    private fun collectIntro(scope: Element): String {
        val label = findLabelElement(scope, Wenku8Selectors.DETAIL_INTRO_LABEL) ?: return ""
        extractLabeledValue(label, Wenku8Selectors.DETAIL_INTRO_LABEL, multiline = true)
            ?.let { if (it.isNotBlank()) return it }

        val raw = label.text()
        val index = raw.indexOf(Wenku8Selectors.DETAIL_INTRO_LABEL)
        if (index < 0) return ""
        return TextCleaner.body(raw.substring(index + Wenku8Selectors.DETAIL_INTRO_LABEL.length))
    }

    private fun collectLatestChapter(scope: Element): String {
        val label = findLabelElement(scope, "最近章节") ?: return ""
        return extractLabeledValue(label, "最近章节", multiline = false).orEmpty()
    }

    /**
     * Reads the value of a `标签：值` row.
     *
     * The list of candidates is exhaustive on purpose and each one is validated by
     * [looksLikeValue], because on this source the value can be:
     * - inline in the label element's own text (`内容简介：正文`),
     * - a bare text node in the label's parent, separated from the label by a
     *   `<br>` (this is the common shape: the `<span>` holds only the label),
     * - the next sibling block,
     * - the remainder of the label element's combined text.
     */
    private fun extractLabeledValue(label: Element, marker: String, multiline: Boolean): String? {
        val candidates = ArrayList<String>(5)
        fun sameLine(text: String): String = TextCleaner.oneLine(text)
        fun multiLine(text: String): String = TextCleaner.body(text)

        fun valueOf(text: String): String = if (multiline) multiLine(text) else sameLine(text)

        label.ownText.toString().let { own ->
            val at = own.indexOf(marker)
            if (at >= 0) candidates.add(valueOf(own.substring(at + marker.length)))
        }
        label.parent?.ownText?.toString()?.let { parentText ->
            val at = parentText.indexOf(marker)
            if (at >= 0) candidates.add(valueOf(cutAtNextLabel(parentText.substring(at + marker.length))))
        }
        followingSiblingBlock(label)?.let { sibling ->
            candidates.add(valueOf(if (multiline) sibling.textWithBreaks() else sibling.text()))
        }
        label.text().let { raw ->
            val at = raw.indexOf(marker)
            if (at >= 0) candidates.add(valueOf(cutAtNextLabel(raw.substring(at + marker.length))))
        }

        return candidates.firstOrNull { (if (multiline) it.isNotBlank() else it.isNotBlank()) && looksLikeValue(it) }
    }

    private fun cutAtNextLabel(text: String): String {
        var cut = text.length
        for (other in OTHER_LABELS) {
            val at = text.indexOf(other)
            if (at in 0 until cut) cut = at
        }
        return text.substring(0, cut)
    }

    /** Rejects a "value" that is really the next label or an empty spacer. */
    private fun looksLikeValue(value: String): Boolean {
        val trimmed = value.trim().trimStart('：', ':', ' ', '\u00a0')
        if (trimmed.isEmpty()) return false
        for (label in OTHER_LABELS) {
            if (trimmed.startsWith(label)) return false
        }
        return true
    }

    private val OTHER_LABELS = listOf(
        "作品Tags", "作品热度", "最近章节", "内容简介",
        "文库分类", "小说作者", "文章状态", "最后更新",
    )

    /**
     * Finds the *innermost* element whose own text carries [marker].
     *
     * A plain `select("*")` returns the shallowest match (the whole `<td>`), which
     * would make "the following sibling" meaningless — hence the two-pass lookup
     * preferring inline elements.
     */
    private fun findLabelElement(scope: Element, marker: String): Element? {
        val all = scope.select("span, b, strong, font, a, div, td, legend")
        return all.firstOrNull { it.ownText.contains(marker) }
            ?: all.firstOrNull { it.text().contains(marker) && it.ownText.isNotBlank() }
            ?: scope.select("*").firstOrNull { it.text().contains(marker) }
    }

    /**
     * Next sibling element of [node] that can actually hold a value.
     *
     * `<br>` is an [Element] here (it is parsed as a void tag), so it has to be
     * skipped — otherwise "the block after the label" is always a line break.
     */
    private fun followingSiblingBlock(node: Element): Element? {
        val parent = node.parent ?: return null
        val siblings = parent.childElements()
        val index = siblings.indexOf(node)
        if (index < 0) return null
        return siblings.drop(index + 1).firstOrNull { it.tag != "br" && it.tag != "hr" }
    }

    private fun collectDownloadLinks(scope: Element): List<Pair<String, String>> =
        scope.select("a[href*=down]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href") ?: return@mapNotNull null
                val label = TextCleaner.oneLine(anchor.text()).ifBlank { return@mapNotNull null }
                label to href
            }
            .distinctBy { it.second }

    /**
     * Locates the volume/chapter table.
     *
     * Candidate selectors first, then a structural fallback: the table that
     * actually contains chapter cells. The structural check is what keeps this
     * working if the source drops the `css` class or moves the table out of any
     * particular container again.
     */
    private fun findTocTable(root: Element): Element? {
        firstMatch(root, Wenku8Selectors.TOC_TABLE)?.let { candidate ->
            if (candidate.selectFirst(Wenku8Selectors.TOC_ANY_CHAPTER_LINK) != null) return candidate
        }
        return root.select("table").firstOrNull { it.selectFirst(Wenku8Selectors.TOC_ANY_CHAPTER_LINK) != null }
    }

    // ------------------------------------------------------------ chapter list page

    fun parseChapterList(html: String, bookId: Int): List<Volume> {
        val root = Html.parse(html)
        val table = findTocTable(root) ?: return emptyList()

        val volumes = ArrayList<Volume>(16)
        var currentTitle = ""
        var currentChapters = ArrayList<Chapter>(32)
        var index = 0
        var volumeSeq = 0

        fun flush() {
            if (currentChapters.isEmpty()) return
            volumes.add(
                Volume(
                    volumeId = volumeSeq,
                    title = currentTitle.ifBlank { "正文" },
                    chapters = currentChapters,
                ),
            )
        }

        for (row in table.select("tr")) {
            val volumeCell = row.selectFirst(Wenku8Selectors.TOC_VOLUME_CELL)
            if (volumeCell != null) {
                val title = TextCleaner.oneLine(volumeCell.text())
                if (title.isNotEmpty() && title != currentTitle) {
                    flush()
                    currentChapters = ArrayList(32)
                    currentTitle = title
                    volumeSeq++
                }
                continue
            }
            // A row can carry several chapters side by side (the source uses a
            // two-column layout), so every chapter cell in the row is consumed.
            for (chapterCell in row.select(Wenku8Selectors.TOC_CHAPTER_CELL)) {
                val anchor = chapterCell.selectFirst(Wenku8Selectors.TOC_CHAPTER_LINK) ?: continue
                val href = anchor.attr("href") ?: continue
                val chapterId = chapterIdOf(href) ?: continue
                currentChapters.add(
                    Chapter(
                        chapterId = chapterId,
                        title = TextCleaner.oneLine(anchor.text()).ifBlank { "第 ${index + 1} 章" },
                        volumeId = volumeSeq,
                        volumeTitle = currentTitle.ifBlank { "正文" },
                        index = index++,
                    ),
                )
            }
        }
        flush()
        return volumes
    }

    /**
     * Matches a chapter id from either URL shape the source emits:
     * - absolute, e.g. `/novel/3/3988/165421.htm`
     * - bare relative, e.g. `165421.htm` (the live catalogue and chapter-body
     *   pages link between chapters with plain relative hrefs).
     *
     * `index.htm` (the volume list) and `/book/{id}.htm` (the detail page) are
     * rejected: neither is preceded by a bare digit or the `/novel/...` prefix.
     */
    private val CHAPTER_ID = Regex("""(?:^|/novel/\d+/\d+/)(\d+)\.htm$""")

    /**
     * Chapter URLs look like `/novel/{cat}/{book}/{chapterId}.htm`, and on the
     * live pages simply `{chapterId}.htm` relative to the volume list.
     */
    fun chapterIdOf(url: String?): Int? {
        val path = url?.substringBefore('?')?.substringBefore('#') ?: return null
        return CHAPTER_ID.find(path)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ------------------------------------------------------------ chapter body page

    fun parseChapterContent(
        html: String,
        bookId: Int,
        chapterId: Int,
        fallbackTitle: String = "",
    ): ChapterContent {
        val root = Html.parse(html)
        val content = firstMatch(root, Wenku8Selectors.CONTENT_ROOT) ?: root

        val blocks = collectChapterBlocks(content)

        val title = content.selectFirst("ul")
            ?.let { ul -> ul.select("li").map { TextCleaner.oneLine(it.text()) }.lastOrNull { it.isNotEmpty() } }
            ?.takeIf { it.isNotBlank() && it.length < 80 && CHINESE_BOOK_TITLE.containsMatchIn(it).not() }
            ?: fallbackTitle

        val links = content.select("ul a[href]").mapNotNull { anchor ->
            anchor.attr("href")?.let { chapterIdOf(it)?.let { id -> anchor to id } }
        }
        val previous = links.firstOrNull { TextCleaner.oneLine(it.first.text()).contains("上一") }?.second
        val next = links.firstOrNull {
            val text = TextCleaner.oneLine(it.first.text())
            text.contains("下一") || text.contains("下一页")
        }?.second

        return ChapterContent(
            bookId = bookId,
            chapterId = chapterId,
            title = title.ifBlank { "第 $chapterId 章" },
            blocks = blocks,
            previousChapterId = previous,
            nextChapterId = next,
            requiresLogin = looksLikeLoginWall(blocks, html),
        )
    }

    private val CHINESE_BOOK_TITLE = Regex("""^\d+\s""")

    /**
     * A chapter request without a valid session comes back as the login page, so
     * the only text present is the login prompt. Detect that shape instead of
     * handing the UI a chapter whose body is a login form.
     */
    private fun looksLikeLoginWall(blocks: List<ContentBlock>, html: String): Boolean {
        val text = blocks.filterIsInstance<ContentBlock.Paragraph>()
            .joinToString("\n") { it.text }
            .take(2_000)
        val isLoginText = text.contains("用户登录") ||
            text.contains("密 码") ||
            text.contains("用户名或邮箱")
        val hasLoginForm = html.contains("login.php") ||
            html.contains("name=\"password\"") ||
            html.contains("name=password")
        return hasLoginForm && (isLoginText || blocks.none { it is ContentBlock.Paragraph && it.text.isNotBlank() })
    }

    /** True when a page is the source's login form rather than the requested content. */
    fun looksLikeLoginPage(html: String): Boolean =
        html.contains("用户登录") && html.contains("login.php") && html.contains("password")

    /** `<title>` of a page, for diagnostics. */
    fun pageTitle(html: String): String =
        Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues?.get(1)
            ?.let { TextCleaner.oneLine(it) }
            .orEmpty()

    /**
     * Collects the chapter body in reading order.
     *
     * The source does not wrap lines in elements: the body is bare text nodes
     * separated by `<br>`, with `<img>` tags inline. So the container's own text
     * is split on newlines (a `<br>` contributes one) and illustrations are
     * spliced back in at their position. Navigation lists and scripts are
     * dropped.
     */
    private fun collectChapterBlocks(content: Element): List<ContentBlock> {
        val body = bodyTextOf(content)
        val illustrations = ArrayList<String>(4)
        content.select("img").forEach { image ->
            val src = image.attr("src") ?: image.attr("data-src")
            Wenku8Urls.coverFromPage(src)?.let { if (it.isNotBlank()) illustrations.add(it) }
        }

        val lines = splitBodyLines(body)
        if (illustrations.isEmpty()) {
            return lines.map { ContentBlock.Paragraph(it) }
        }

        // The source places 插图 chapters as images between text blocks, so the
        // illustrations are spread across the extracted lines.
        val blocks = ArrayList<ContentBlock>(lines.size + illustrations.size)
        val textCount = lines.count { it.isNotBlank() }.coerceAtLeast(1)
        val step = maxOf(1, textCount / (illustrations.size + 1))
        var imageIndex = 0
        var textSoFar = 0
        for (line in lines) {
            blocks.add(ContentBlock.Paragraph(line))
            if (line.isNotBlank()) {
                textSoFar++
                if (imageIndex < illustrations.size && textSoFar % step == 0) {
                    blocks.add(ContentBlock.Illustration(illustrations[imageIndex]))
                    imageIndex++
                }
            }
        }
        while (imageIndex < illustrations.size) {
            blocks.add(ContentBlock.Illustration(illustrations[imageIndex]))
            imageIndex++
        }
        return blocks
    }

    /** Container tags whose text is not part of the chapter body. */
    private val BODY_SKIP_TAGS = setOf("ul", "ol", "script", "style")

    private fun bodyTextOf(element: Element): String {
        val out = StringBuilder()
        fun walk(node: Element) {
            if (node.ownText.isNotEmpty()) out.append(node.ownText)
            for (child in node.children) {
                if (child.tag == "br") {
                    out.append('\n')
                    continue
                }
                if (child.tag in BODY_SKIP_TAGS) continue
                walk(child)
            }
        }
        walk(element)
        return out.toString()
    }

    /**
     * A `<br>` is a paragraph break. Runs of breaks collapse into a single break,
     * and a break before the first line is dropped.
     */
    private fun splitBodyLines(body: String): List<String> {
        val lines = ArrayList<String>(64)
        val buffer = StringBuilder()
        for (ch in body) {
            if (ch == '\n') {
                val text = TextCleaner.oneLine(buffer.toString())
                buffer.setLength(0)
                if (lines.isEmpty() && text.isEmpty()) continue
                if (text.isEmpty() && lines.lastOrNull()?.isEmpty() == true) continue
                lines.add(text)
            } else {
                buffer.append(ch)
            }
        }
        TextCleaner.oneLine(buffer.toString()).takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.size - 1)
        return lines
    }

    /**
     * Turns the raw block stream into final paragraphs.
     *
     * A `<br>` produces an empty paragraph, which is exactly a paragraph break on
     * this source; runs of empty separators collapse, and images keep their place
     * in the reading order. Merging on blanks would glue unrelated paragraphs
     * together, so a blank always flushes the buffer.
     */
    fun normalizeBlocks(blocks: List<ContentBlock>): List<ContentBlock> {
        val result = ArrayList<ContentBlock>(blocks.size)
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            val text = TextCleaner.oneLine(buffer.toString())
            buffer.setLength(0)
            if (text.isNotEmpty()) result.add(ContentBlock.Paragraph(text))
        }

        for (block in blocks) {
            when (block) {
                is ContentBlock.Illustration -> {
                    flush()
                    if (result.lastOrNull() != block) result.add(block)
                }

                is ContentBlock.Paragraph -> {
                    val text = block.text.replace('\u00a0', ' ').trim()
                    if (text.isEmpty()) {
                        flush()
                    } else {
                        if (buffer.isNotEmpty()) buffer.append(' ')
                        buffer.append(text)
                    }
                }
            }
        }
        flush()
        return result
    }

    // ------------------------------------------------------------------ list pages

    /**
     * Ranking / catalog / search pages put every entry inside one big table cell,
     * each entry being a `div` carrying a cover, the book link and an "加入书架"
     * link. Entries are located by walking up from the 加入书架 anchor.
     */
    fun parseRankingList(html: String): List<Book> {
        val root = Html.parse(html)
        val books = LinkedHashMap<Int, Book>()

        val containers = LinkedHashSet<Element>()
        for (anchor in root.select(Wenku8Selectors.LIST_ONLINE_SHELF_ADD)) {
            containerOf(anchor)?.let { containers.add(it) }
        }
        if (containers.isEmpty()) {
            for (anchor in root.select(Wenku8Selectors.LIST_BOOK_LINK)) {
                containerOf(anchor)?.let { containers.add(it) }
            }
        }

        for (container in containers) {
            // An entry links to its book twice: the first link wraps only the
            // cover and therefore has **no text**, the second one carries the
            // title. Taking the first link blindly made every entry look
            // untitled, so the whole list silently fell back to [parseBookList]
            // — which is why search results and rankings had no covers.
            val bookAnchor = container.select(Wenku8Selectors.LIST_BOOK_LINK)
                .firstOrNull { anchor ->
                    Wenku8Urls.bookIdOf(anchor.attr("href")) != null &&
                        TextCleaner.oneLine(anchor.text()).let { it.isNotEmpty() && it !in ENTRY_ACTION_LABELS }
                } ?: continue
            val bookId = Wenku8Urls.bookIdOf(bookAnchor.attr("href")) ?: continue
            val title = TextCleaner.oneLine(bookAnchor.text())
            if (title.isEmpty()) continue

            val plain = TextCleaner.oneLine(container.text())
            val author = Wenku8Selectors.LIST_AUTHOR.find(plain)?.groupValues?.get(1).orEmpty()

            books[bookId] = Book(
                bookId = bookId,
                title = title,
                shortTitle = title,
                author = author,
                coverUrl = Wenku8Urls.coverFromPage(entryCoverOf(container, bookId)),
            )
        }
        return books.values.toList()
    }

    /** Action links inside an entry, which must never be mistaken for its title. */
    private val ENTRY_ACTION_LABELS = setOf("我要阅读", "加入书架", "开始阅读", "阅读")

    /**
     * Cover of one entry.
     *
     * The cover sits inside the link to the *same* book, and that check is what
     * keeps a container holding several entries from giving every book the first
     * entry's cover. A plain first image is still accepted, because a container
     * that reaches past one entry starts at its first one anyway.
     */
    private fun entryCoverOf(container: Element, bookId: Int): String? {
        val images = container.select("img")
        val linked = images.firstOrNull { image ->
            val parent = image.parent
            parent?.tag == "a" && Wenku8Urls.bookIdOf(parent.attr("href")) == bookId
        }
        return (linked ?: images.firstOrNull())?.attr("src")
    }

    /** Smallest ancestor that still looks like a single list entry. */
    private fun containerOf(anchor: Element): Element? {
        var node: Element? = anchor
        while (node != null && node.tag != "body" && node.tag != "#document") {
            if (node.selectFirst("img") != null) return node
            node = node.parent
        }
        return anchor.parent
    }

    /**
     * The user's online bookshelf.
     *
     * Each row holds the book's title, the author's own link, the latest chapter and a
     * checkbox identifying the row. The row is located by walking up from the entry link
     * to the nearest ancestor holding that row's checkbox — the bookshelf has no per-row
     * delete anchor to key off, which is what the previous implementation looked for and
     * therefore never found, leaving every bookcase entry without its author.
     */
    fun parseBookcase(html: String): List<Book> {
        val root = Html.parse(html)
        val books = LinkedHashMap<Int, Book>()

        for (anchor in root.select(Wenku8Selectors.BOOKCASE_ENTRY)) {
            val href = anchor.attr("href") ?: continue
            val bookId = Wenku8Urls.bookIdOfBookcase(href) ?: continue
            val title = TextCleaner.oneLine(anchor.text())
            if (title.isEmpty()) continue

            val scope = bookcaseRow(anchor) ?: anchor.parent ?: anchor
            val author = scope.selectFirst(Wenku8Selectors.BOOKCASE_AUTHOR_LINK)
                ?.let { TextCleaner.oneLine(it.text()) }
                .orEmpty()

            val existing = books[bookId]
            val isChapterLink = Wenku8Urls.chapterIdOfBookcase(href) != null
            if (existing == null) {
                books[bookId] = Book(
                    bookId = bookId,
                    title = title,
                    shortTitle = title,
                    author = author,
                    latestChapter = if (isChapterLink) title else "",
                )
            } else if (isChapterLink) {
                books[bookId] = existing.copy(latestChapter = title)
            }
        }
        return books.values.toList()
    }

    /**
     * Maps each book's id to the bookshelf's own id for its row.
     *
     * The site uses two different numbers for the same book. The detail page and every
     * reader URL use `aid` (e.g. `3988`), which is the id this app calls `bookId`; the
     * bookshelf's checkbox, its 移除 control and its bulk form all use `bid` (e.g.
     * `13066825`). Removal only accepts the latter, so the pairing has to be read off
     * the page rather than derived.
     */
    fun parseBookcaseRowIds(html: String): Map<Int, String> {
        val root = Html.parse(html)
        val rowIds = LinkedHashMap<Int, String>()
        for (input in root.select("input")) {
            if (input.attr("type") != "checkbox") continue
            val name = input.attr("name") ?: continue
            if (!name.startsWith(Wenku8Selectors.BOOKCASE_ROW_CHECKBOX_NAME)) continue
            val shelfId = input.attr("value")?.takeIf { it.isNotBlank() } ?: continue
            val bookId = bookcaseRow(input)?.let(::bookIdOfRow) ?: continue
            rowIds[bookId] = shelfId
        }
        return rowIds
    }

    /** The bulk-action form of the bookshelf page, or null when the page has none. */
    fun parseBookcaseActionForm(html: String): BookcaseActionForm? {
        val root = Html.parse(html)
        val select = root.select("select").firstOrNull {
            it.attr("name") == Wenku8Selectors.BOOKCASE_ACTION_SELECT_NAME
        } ?: return null
        val actionField = select.attr("name")?.takeIf { it.isNotBlank() } ?: return null

        var form: Element? = select
        while (form != null && form.tag != "form") form = form.parent
        val scope: Element = form ?: root

        val inputs = scope.select("input")
        val selectionField = inputs.firstOrNull { input ->
            input.attr("type") == "checkbox" &&
                input.attr("name")?.startsWith(Wenku8Selectors.BOOKCASE_ROW_CHECKBOX_NAME) == true
        }?.attr("name")?.takeIf { it.isNotBlank() } ?: return null

        val hidden = LinkedHashMap<String, String>()
        for (input in inputs) {
            if (input.attr("type") != "hidden") continue
            val name = input.attr("name")?.takeIf { it.isNotBlank() } ?: continue
            hidden[name] = input.attr("value").orEmpty()
        }

        val submit = inputs.firstOrNull { it.attr("type") == "submit" }

        return BookcaseActionForm(
            // The live page ships `action=""`, meaning "post back here"; resolving that
            // through absoluteUrl would yield null, so the bookshelf URL is the fallback.
            action = Wenku8Urls.absoluteUrl(form?.attr("action")) ?: Wenku8Urls.BOOKCASE,
            selectionField = selectionField,
            actionField = actionField,
            hidden = hidden,
            submitField = submit?.attr("name")?.takeIf { it.isNotBlank() },
            submitValue = submit?.attr("value").orEmpty(),
        )
    }

    /**
     * The counts in the header: `您的书架可收藏 300 本，已收藏 3 本，本组有 3 本。`
     *
     * Parsed rather than assumed, because the list below is paged and grouped: its length
     * is the current group's page, never the account's total.
     */
    fun parseBookcaseSummary(html: String): BookcaseSummary? {
        val header = Html.parse(html).selectFirst(Wenku8Selectors.BOOKCASE_SUMMARY) ?: return null
        val match = BOOKCASE_COUNTS.find(TextCleaner.oneLine(header.text())) ?: return null
        return BookcaseSummary(
            capacity = match.groupValues[1].toIntOrNull() ?: return null,
            total = match.groupValues[2].toIntOrNull() ?: return null,
            inGroup = match.groupValues[3].toIntOrNull() ?: return null,
        )
    }

    /**
     * Nearest ancestor of [node] that is a whole bookshelf row — i.e. the `<tr>`.
     *
     * Requires *both* the row's checkbox and an entry link. Either test alone lands on
     * the wrong element: the checkbox on its own is satisfied by its own `<td>`, and an
     * entry link on its own is satisfied by the title's `<td>`, which holds no author.
     */
    private fun bookcaseRow(node: Element): Element? {
        var current: Element? = node
        while (current != null && current.tag != "#document") {
            val hasCheckbox = current.descendants().any { it.isBookcaseRowCheckbox() }
            if (hasCheckbox && current.selectFirst(Wenku8Selectors.BOOKCASE_ENTRY) != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun Element.isBookcaseRowCheckbox(): Boolean =
        tag == "input" &&
            attr("type") == "checkbox" &&
            attr("name")?.startsWith(Wenku8Selectors.BOOKCASE_ROW_CHECKBOX_NAME) == true

    /** Book id the entry links inside [row] point at. */
    private fun bookIdOfRow(row: Element): Int? =
        row.select(Wenku8Selectors.BOOKCASE_ENTRY)
            .firstNotNullOfOrNull { Wenku8Urls.bookIdOfBookcase(it.attr("href")) }

    /** Trailing `作者:…` / `状态:…` values on a ranking entry. */
    fun parseRankingMeta(entryText: String): Pair<String, String> {
        val author = Wenku8Selectors.LIST_AUTHOR.find(entryText)?.groupValues?.get(1).orEmpty()
        val status = Regex("""状态[:：]\s*([^\s/]+)""").find(entryText)?.groupValues?.get(1).orEmpty()
        return author to status
    }

    /**
     * Parses a list of books from ranking pages, the catalog or search results.
     *
     * Ranking pages expose `a[href*=/book/]` entries; catalog/search pages use
     * plain tables, so both shapes are handled and de-duplicated by book id.
     */
    fun parseBookList(html: String, categoryHint: String = ""): List<Book> {
        val root = Html.parse(html)
        val books = LinkedHashMap<Int, Book>()

        for (anchor in root.select(Wenku8Selectors.LIST_BOOK_LINK)) {
            val href = anchor.attr("href") ?: continue
            val bookId = Wenku8Urls.bookIdOf(href) ?: continue
            val title = TextCleaner.oneLine(anchor.text())
            if (title.isEmpty()) continue
            val existing = books[bookId]
            if (existing == null || existing.title.length < title.length) {
                books[bookId] = Book(
                    bookId = bookId,
                    title = title,
                    shortTitle = title,
                    category = categoryHint,
                )
            }
        }
        return books.values.toList()
    }

    /**
     * Parses the "最近更新" block of the homepage, whose rows carry the library
     * name, the truncated title, the latest chapter, the author and the date.
     */
    fun parseRecentUpdates(html: String): List<Book> {
        val root = Html.parse(html)
        val books = LinkedHashMap<Int, Book>()
        for (item in root.select(Wenku8Selectors.HOME_RECENT_ITEM)) {
            val anchors = item.select("a[href]")
            if (anchors.size < 2) continue
            val bookAnchor = anchors.firstOrNull { Wenku8Urls.bookIdOf(it.attr("href")) != null } ?: continue
            val bookId = Wenku8Urls.bookIdOf(bookAnchor.attr("href")) ?: continue
            val latestAnchor = anchors.firstOrNull { Wenku8Urls.novelPrefixOf(it.attr("href")) != null }

            val rawTitle = TextCleaner.oneLine(bookAnchor.text())
            val itemText = TextCleaner.oneLine(item.text())
            val entryMatch: MatchResult? = Wenku8Selectors.RECENT_ENTRY.find(itemText)
            // Groups: 1 = [文库], 2 = 《标题》, 3 = 最新章节
            val entryTitle = entryMatch?.groupValues?.getOrNull(2).orEmpty()
            val entryCategory = entryMatch?.groupValues?.getOrNull(1).orEmpty()

            // The author + date line is the entry's neighbouring row ("作者名 (MM-DD)").
            val authorDate = sequenceOf(itemText)
                .plus(siblingTexts(item))
                .plus(item.select("li").map { TextCleaner.oneLine(it.text()) })
                .firstNotNullOfOrNull { text ->
                    Wenku8Selectors.RECENT_AUTHOR_DATE.find(text)?.let {
                        it.groupValues[1].trim() to "${it.groupValues[2]}-${it.groupValues[3]}"
                    }
                }

            // The live rows carry no cover image, only the category in `[文库]`
            // (a name, not the numeric segment) and the latest-chapter link, so
            // the cover path is derived from the category inside that URL:
            // `/novel/3/3475/178773.htm` + aid 3475 -> `…/image/3/3475/3475s.jpg`.
            val coverInMarkup = Wenku8Urls.coverFromPage(
                item.selectFirst(Wenku8Selectors.HOME_RECENT_COVER)?.attr("src"),
            )
            val coverUrl = coverInMarkup
                ?: Wenku8Urls.coverFromNovelUrl(bookId, latestAnchor?.attr("href"))

            books[bookId] = Book(
                bookId = bookId,
                title = rawTitle.ifBlank { entryTitle },
                shortTitle = entryTitle.ifBlank { rawTitle },
                author = authorDate?.first.orEmpty(),
                coverUrl = coverUrl,
                category = entryCategory,
                latestChapter = latestAnchor?.let { TextCleaner.oneLine(it.text()) }.orEmpty(),
                updatedAt = authorDate?.second.orEmpty(),
            )
        }
        return books.values.toList()
    }

    /** Text of the elements around [node] — the source alternates entry / author rows. */
    private fun siblingTexts(node: Element): List<String> {
        val parent = node.parent ?: return emptyList()
        val siblings = parent.childElements()
        val index = siblings.indexOf(node)
        if (index < 0) return emptyList()
        val out = ArrayList<String>(2)
        siblings.getOrNull(index - 1)?.let { out.add(TextCleaner.oneLine(it.text())) }
        siblings.getOrNull(index + 1)?.let { out.add(TextCleaner.oneLine(it.text())) }
        return out
    }

    /** Ranking URLs embedded in a page, used to discover the sortable lists. */
    fun parseRankLinks(html: String): Map<RankType, String> {
        val root = Html.parse(html)
        val found = LinkedHashMap<RankType, String>()
        for (anchor in root.select("a[href*=toplist]")) {
            val href = anchor.attr("href") ?: continue
            val sort = Regex("""sort=([a-z]+)""").find(href)?.groupValues?.get(1) ?: continue
            val type = RankType.entries.firstOrNull { it.sort == sort } ?: continue
            found.putIfAbsent(type, href)
        }
        return found
    }

    private fun firstMatch(root: Element, selectors: List<String>): Element? {
        for (selector in selectors) {
            root.selectFirst(selector)?.let { return it }
        }
        return null
    }
}
