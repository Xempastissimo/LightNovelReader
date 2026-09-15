package com.xempastissimo.lightnovelreader.ui.screen.shelf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import com.xempastissimo.lightnovelreader.data.repo.formatBytes
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.domain.model.ShelfEntry
import com.xempastissimo.lightnovelreader.ui.component.EmptyBox
import com.xempastissimo.lightnovelreader.ui.component.LoadingBox
import com.xempastissimo.lightnovelreader.ui.component.ShelfRow
import com.xempastissimo.lightnovelreader.ui.component.StaggeredEntrance
import com.xempastissimo.lightnovelreader.ui.component.StateCrossfade
import com.xempastissimo.lightnovelreader.ui.component.RotatingIcon

/** Which of the shelf's mutually exclusive bodies is on show. */
private enum class ShelfPhase { LOADING, ERROR, EMPTY, CONTENT }

/**
 * Everything the shelf can do, in one value.
 *
 * Bundled so the screen's body takes two parameters instead of fourteen, and so a preview
 * can render the whole shelf by supplying only the state: every action has a default, which
 * makes the preview independent of the view model and turns a forgotten callback into a
 * compile error at the call site rather than a dead button in the panel.
 */
data class ShelfActions(
    val onSelectTab: (ShelfTab) -> Unit = {},
    val onStartSelecting: () -> Unit = {},
    val onStopSelecting: () -> Unit = {},
    val onToggleSelection: (Int) -> Unit = {},
    val onToggleSelectAll: () -> Unit = {},
    val onRemoveSelected: () -> Unit = {},
    val onSyncOnlineShelf: () -> Unit = {},
    val onRemove: (ShelfEntry) -> Unit = {},
    val onDeleteOffline: (ShelfEntry) -> Unit = {},
    val onDeleteDownloaded: (ShelfEntry) -> Unit = {},
    /** The bytes 已缓存 holds for one book, so the removal question can name the cost. */
    val offlineSizeBytes: (Int) -> Long = { 0L },
    val onOpenBook: (Book) -> Unit = {},
    val onContinueReading: (Int, Int) -> Unit = { _, _ -> },
    val onOpenSearch: () -> Unit = {},
    val onOpenLogin: () -> Unit = {},
)

/**
 * The shelf itself: four lists that look alike and mean different things.
 *
 * Stateless apart from which confirmation or row menu is open — that is view state, not
 * shelf state, and keeping it here is what lets the panel render the screen without a view
 * model. Nothing in here talks to the network or the file system; it renders
 * [ShelfUiState] and reports taps through [ShelfActions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfContent(
    state: ShelfUiState,
    actions: ShelfActions = ShelfActions(),
) {
    var pendingRemoval by remember { mutableStateOf<ShelfEntry?>(null) }
    var pendingOfflineDelete by remember { mutableStateOf<ShelfEntry?>(null) }
    var pendingDownloadDelete by remember { mutableStateOf<ShelfEntry?>(null) }
    var pendingMultiSelectRemoval by remember { mutableStateOf(false) }
    var menuBookId by remember { mutableStateOf<Int?>(null) }

    // The two tabs below the site's own shelf are local; only they carry a copy that can be
    // deleted on its own, and the wording of every delete follows from which one is on show.
    val localTab = state.tab == ShelfTab.CACHED || state.tab == ShelfTab.DOWNLOADED

    // How many chapters a book has, when this screen happens to know: a downloaded pack stores
    // its own catalogue, so those rows can carry a real reading-progress bar. A book whose
    // catalogue has never been read here gets no bar at all, because an invented denominator
    // would be a made-up percentage.
    fun chapterTotalOf(bookId: Int): Int = state.downloads[bookId]?.tocTotal ?: 0

    fun requestDelete(entry: ShelfEntry) {
        when (state.tab) {
            ShelfTab.DOWNLOADED -> pendingDownloadDelete = entry
            ShelfTab.CACHED -> pendingOfflineDelete = entry
            else -> pendingRemoval = entry
        }
    }

    fun deleteLabelFor(tab: ShelfTab): String = when (tab) {
        ShelfTab.DOWNLOADED -> "删除本地下载"
        ShelfTab.CACHED -> "删除本地缓存"
        else -> "从书架移除"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.tab == ShelfTab.SHELF && state.selecting) {
            TopAppBar(
                title = { Text("已选 ${state.selection.size} 本") },
                navigationIcon = {
                    IconButton(onClick = actions.onStopSelecting) {
                        Icon(Icons.Filled.Close, contentDescription = "退出多选")
                    }
                },
                actions = {
                    TextButton(onClick = actions.onToggleSelectAll) {
                        Text(if (state.allVisibleSelected) "取消全选" else "全选")
                    }
                    IconButton(
                        onClick = { pendingMultiSelectRemoval = true },
                        enabled = state.selection.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "移出书架")
                    }
                },
            )
        } else {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    if (state.tab == ShelfTab.SHELF) {
                        IconButton(onClick = actions.onStartSelecting) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "多选")
                        }
                        IconButton(onClick = actions.onSyncOnlineShelf) {
                            RotatingIcon(isRefreshing = state.syncingOnline) {
                                Icon(Icons.Filled.Refresh, contentDescription = "同步站点在线书架")
                            }
                        }
                    }
                },
            )
        }

        ScrollableTabRow(
            selectedTabIndex = ShelfTab.entries.indexOf(state.tab),
            edgePadding = 12.dp,
        ) {
            ShelfTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { actions.onSelectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        // How much of the site's bookshelf is in use. The site caps this, so the number
        // is shown against its limit rather than on its own.
        if (state.tab == ShelfTab.SHELF && state.onlineCapacity > 0) {
            OnlineShelfCount(
                count = state.onlineCount,
                capacity = state.onlineCapacity,
                inGroup = state.entries.size,
                full = state.shelfFull,
            )
        }

        // The local tabs' own count: what is on this device, and what it costs. Only the
        // total space is worth a line here — unlike the shelf there is no cap to measure
        // against, but there is a reason to want the number down.
        if (state.tab == ShelfTab.CACHED && state.offline.isNotEmpty()) {
            LocalCount(count = state.offline.size, bytes = state.offlineBytes)
        }
        if (state.tab == ShelfTab.DOWNLOADED && state.downloads.isNotEmpty()) {
            LocalCount(count = state.downloads.size, bytes = state.downloadBytes)
        }

        // A shelf sync belongs to the 书架 tab only. It runs in the background when the
        // screen opens (or when the user returns from logging in), and letting it drive
        // the phase for the other tabs too would put a spinner over the reading history or
        // the local cache for a request that has nothing to do with either.
        val syncing = state.syncingOnline && state.tab == ShelfTab.SHELF
        val phase = when {
            syncing || state.loading -> ShelfPhase.LOADING
            // A fetch was attempted and failed: say why instead of showing an
            // empty shelf, which reads as "you have no books". The error belongs to the
            // site's bookshelf, so it must not stand in for the local tabs either.
            state.error != null && state.entries.isEmpty() && state.tab == ShelfTab.SHELF ->
                ShelfPhase.ERROR

            state.entries.isEmpty() -> ShelfPhase.EMPTY
            else -> ShelfPhase.CONTENT
        }

        // Fades when the tab or the body changes, so switching shelves cross-fades
        // rather than swapping rows in place.
        StateCrossfade(
            targetState = state.tab to phase,
            label = "shelf-body",
            modifier = Modifier.weight(1f),
        ) { (tab, body) ->
            when (body) {
                ShelfPhase.LOADING -> LoadingBox(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                    label = when (tab) {
                        ShelfTab.SHELF -> "正在读取站点在线书架…"
                        ShelfTab.CACHED -> "正在读取本机缓存…"
                        ShelfTab.DOWNLOADED -> "正在读取本机下载…"
                        ShelfTab.RECENT -> "读取本地书架…"
                    },
                )

                ShelfPhase.ERROR -> EmptyBox(
                    title = if (state.requiresLogin) "站点在线书架需要登录" else "站点在线书架读取失败",
                    hint = state.error,
                    actionLabel = "重试",
                    onAction = actions.onSyncOnlineShelf,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                )

                ShelfPhase.EMPTY -> EmptyBox(
                    title = when (tab) {
                        ShelfTab.RECENT -> "还没有阅读记录"
                        ShelfTab.SHELF -> if (state.loggedIn) "站点在线书架还是空的" else "登录后查看站点书架"
                        ShelfTab.CACHED -> "还没有缓存任何轻小说"
                        ShelfTab.DOWNLOADED -> "还没有下载任何整本"
                    },
                    hint = when (tab) {
                        ShelfTab.RECENT -> "打开任意一本书开始阅读，进度会自动记录"
                        ShelfTab.SHELF -> if (state.loggedIn) {
                            "书架展示的是站点账号里的收藏。在书籍详情页点星标即可加入，" +
                                "也可以点右上角刷新重新读取"
                        } else {
                            "书架展示的是站点账号里的收藏，需要先登录才能读取"
                        }

                        ShelfTab.CACHED ->
                            "在书籍详情页点「缓存全本」，整本书会按章下载到本机，" +
                                "之后没有网络也能阅读；这里只显示已经缓存了章节的书"

                        ShelfTab.DOWNLOADED ->
                            "在书籍详情页点「下载全本（站点打包）」，站点会把整本打包成一个 txt " +
                                "一次下载到本机，比逐章缓存快得多；打包文件不含插图。" +
                                "已下载的书不会重复出现在「已缓存」里"
                    },
                    actionLabel = if (tab == ShelfTab.SHELF && !state.loggedIn) "去登录" else "去搜索",
                    onAction = {
                        if (tab == ShelfTab.SHELF && !state.loggedIn) actions.onOpenLogin() else actions.onOpenSearch()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp),
                )

                ShelfPhase.CONTENT -> key(state.entries.hashCode()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        itemsIndexed(state.entries, key = { _, entry -> entry.book.bookId }) { index, entry ->
                            val bookId = entry.book.bookId
                            val selected = bookId in state.selection
                            val cachedCopy = state.offline[bookId]
                            val download = state.downloads[bookId]
                            StaggeredEntrance(index = index) {
                                Box {
                                    ShelfRow(
                                        book = entry.book,
                                        progressText = entry.progress?.let { progress ->
                                            "读到 第 ${progress.chapterIndex + 1} 章" +
                                                if (entry.book.latestChapter.isNotBlank()) {
                                                    " · 最新：${entry.book.latestChapter}"
                                                } else {
                                                    ""
                                                }
                                        },
                                        progressFraction = entry.progress?.let { progress ->
                                            val total = chapterTotalOf(entry.book.bookId)
                                            if (total > 0) {
                                                (progress.chapterIndex + 1).toFloat() / total
                                            } else {
                                                null
                                            }
                                        },
                                        offline = download != null || cachedCopy != null,
                                        offlineText = when {
                                            download != null ->
                                                "已下载整本 · 覆盖 ${download.covered}/${download.tocTotal} 章" +
                                                    " · ${formatBytes(download.bytes)}"

                                            cachedCopy != null ->
                                                "已缓存 ${cachedCopy.chapterCount} 章 · ${formatBytes(cachedCopy.sizeBytes)}"

                                            else -> null
                                        },
                                        onClick = {
                                            if (state.selecting) {
                                                actions.onToggleSelection(bookId)
                                            } else {
                                                val chapterId = entry.progress?.chapterId
                                                if (chapterId != null && chapterId > 0) {
                                                    actions.onContinueReading(bookId, chapterId)
                                                } else {
                                                    actions.onOpenBook(entry.book)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (state.selecting) actions.onToggleSelection(bookId)
                                            else menuBookId = bookId
                                        },
                                        modifier = Modifier.animateItem(),
                                        trailing = {
                                            if (state.selecting) {
                                                Checkbox(checked = selected, onCheckedChange = null)
                                            } else {
                                                IconButton(onClick = { requestDelete(entry) }) {
                                                    Icon(
                                                        Icons.Filled.Delete,
                                                        contentDescription = deleteLabelFor(state.tab),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    if (!state.selecting) {
                                        RowMenu(
                                            expanded = menuBookId == bookId,
                                            onDismiss = { menuBookId = null },
                                            onOpenDetail = {
                                                menuBookId = null
                                                actions.onOpenBook(entry.book)
                                            },
                                            deleteLabel = deleteLabelFor(state.tab),
                                            onDelete = {
                                                menuBookId = null
                                                requestDelete(entry)
                                            },
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // Removing now always reaches the account's bookshelf on the site, so the tap asks
    // first and says plainly what else it takes with it.
    pendingRemoval?.let { entry ->
        // The delete reaches the chapter files too, so it says how much that costs when the
        // screen knows: "删除本机已缓存的章节" is otherwise a size the user never sees.
        val cachedBytes = actions.offlineSizeBytes(entry.book.bookId)
        RemoveBookDialog(
            title = "移除《${entry.book.title}》？",
            body = buildString {
                append("会从站点在线书架中移除，并删除本机")
                if (cachedBytes > 0) append("已缓存的 ${formatBytes(cachedBytes)} 章节")
                else append("已缓存的章节")
                append("。")
                if (entry.progress != null) {
                    append("阅读进度会一并清除。")
                }
            },
            confirmLabel = "移除",
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                actions.onRemove(entry)
            },
        )
    }

    // The 已缓存 tab's delete asks too, but about something smaller: only the copy on this
    // device goes, and saying so is what keeps the question from reading like the one above.
    pendingOfflineDelete?.let { entry ->
        val cached = state.offline[entry.book.bookId]
        RemoveBookDialog(
            title = "删除《${entry.book.title}》的本地缓存？",
            body = buildString {
                append("会删除本机已缓存的")
                if (cached != null) append(" ${cached.chapterCount} 章（${formatBytes(cached.sizeBytes)}）")
                append("，站点在线书架与阅读进度都不受影响。")
            },
            confirmLabel = "删除",
            onDismiss = { pendingOfflineDelete = null },
            onConfirm = {
                pendingOfflineDelete = null
                actions.onDeleteOffline(entry)
            },
        )
    }

    // The 已下载 tab's delete is the largest of the three: the pack is a second copy of the
    // text, so it has to say that both it and the chapters go — and the bookmarks, which are
    // notes about the downloaded copy and have nothing left to point at once it is gone.
    pendingDownloadDelete?.let { entry ->
        val pack = state.downloads[entry.book.bookId]
        val bookmarkCount = state.bookmarkCounts[entry.book.bookId] ?: 0
        RemoveBookDialog(
            title = "删除《${entry.book.title}》的本机下载？",
            body = buildString {
                append("会删除打包下载的 txt")
                if (pack != null) append("（${formatBytes(pack.bytes)}）")
                append("与本机已缓存的章节，站点在线书架与阅读进度都不受影响。")
                if (bookmarkCount > 0) {
                    append("本书的 $bookmarkCount 条本地书签也会一并删除。")
                }
            },
            confirmLabel = "删除",
            onDismiss = { pendingDownloadDelete = null },
            onConfirm = {
                pendingDownloadDelete = null
                actions.onDeleteDownloaded(entry)
            },
        )
    }

    // Multi-select removal confirmation dialog
    if (pendingMultiSelectRemoval && state.selection.isNotEmpty()) {
        RemoveBookDialog(
            title = "移除选中的 ${state.selection.size} 本书？",
            body = "会从站点在线书架中移除选中的所有书籍，并删除本机已缓存的章节。阅读进度会一并清除。",
            confirmLabel = "移除",
            onDismiss = { pendingMultiSelectRemoval = false },
            onConfirm = {
                pendingMultiSelectRemoval = false
                actions.onRemoveSelected()
            },
        )
    }
}

/** How opaque the confirmation card is; the shelf stays faintly visible through it. */
private const val CONFIRM_SURFACE_ALPHA = 0.88f

/**
 * The removal confirmation.
 *
 * Deliberately not a centred `AlertDialog`: the card is translucent and sits low on the
 * screen, so the row being asked about stays in view behind it and the question reads as
 * being about that row rather than as a screen-level interruption.
 *
 * Keeping the card low needs a full-screen dialog window, which costs the platform's own
 * tap-outside dismissal — nothing is outside a full-screen window — so the scrim handles
 * it. Both tap handlers are `pointerInput` rather than `clickable` so that neither the
 * scrim nor the card picks up a click semantic a screen reader would announce.
 */
@Composable
private fun RemoveBookDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        visible = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(
                initialScale = 0.9f,
                animationSpec = tween(200),
            ),
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                targetScale = 0.9f,
                animationSpec = tween(150),
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .fillMaxWidth()
                        // Swallows taps that land on the card so they never reach the scrim.
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = CONFIRM_SURFACE_ALPHA),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Warning icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Title
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        // Body
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("取消")
                            }
                            Button(
                                onClick = onConfirm,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 2.dp,
                                    pressedElevation = 4.dp,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(confirmLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How much of the site's bookshelf is in use, against the site's own cap. */
@Composable
private fun OnlineShelfCount(count: Int, capacity: Int, inGroup: Int, full: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "共 $count / $capacity 本",
                style = MaterialTheme.typography.labelMedium,
                color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The list can be shorter than the total because the site pages and groups its
            // bookshelf while this app reads only the default group. Saying so beats a list
            // that looks inexplicably truncated.
            if (inGroup != count) {
                Text(
                    text = "本组 $inGroup 本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (full) {
            Text(
                text = "已达站点上限",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * What the device is holding for local reading, on either of the two local tabs.
 *
 * The count and the space are the two things those tabs exist to answer, and the space is
 * the one a user comes here to reduce.
 */
@Composable
private fun LocalCount(count: Int, bytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "共 $count 本",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "占用 ${formatBytes(bytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The actions a shelf row offers on long press. */
@Composable
private fun RowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    deleteLabel: String,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("查看简介") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = onOpenDetail,
        )
        DropdownMenuItem(
            text = { Text(deleteLabel) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = onDelete,
        )
    }
}
