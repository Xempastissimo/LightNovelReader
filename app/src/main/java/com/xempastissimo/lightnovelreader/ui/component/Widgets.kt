package com.xempastissimo.lightnovelreader.ui.component

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xempastissimo.lightnovelreader.domain.model.Book
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme
import kotlin.math.sin

/**
 * Shared list widgets.
 *
 * The source's ranking pages present entries in a grid, the catalog as rows, and
 * the shelf as rows-with-progress, so three shapes are provided here instead of
 * one over-configurable component.
 */

/** Rich entry used by the ranking tabs. */
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The card's own clickable is used rather than a plain one so it keeps the
    // pressed-elevation behaviour, while sharing the interaction source with the
    // scale so both react to the same finger.
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        interactionSource = interactionSource,
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            CoverImage(
                url = book.coverUrl,
                title = book.title,
                modifier = Modifier
                    .size(width = 64.dp, height = 88.dp),
            )
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = "作者：${book.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.category.isNotBlank()) {
                    Text(
                        text = "分类：${book.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.updatedAt.isNotBlank()) {
                    Text(
                        text = "最近更新：${book.updatedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.latestChapter.isNotBlank()) {
                    Text(
                        text = "最新：${book.latestChapter}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Compact row for search results and the catalog. */
@Composable
fun BookRow(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .pressHighlight(interactionSource)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookRowContent(book = book, trailing = trailing)
    }
}

/**
 * The inside of a book row, without a click target of its own.
 *
 * Kept separate so [ShelfRow] can wrap the cover/title line together with its
 * progress line in a *single* tappable row; giving each half its own `clickable`
 * used to make one tap fire two ripples and two callbacks.
 */
@Composable
private fun RowScope.BookRowContent(
    book: Book,
    trailing: @Composable (() -> Unit)? = null,
) {
    CoverImage(
        url = book.coverUrl,
        title = book.title,
        modifier = Modifier.size(width = 48.dp, height = 64.dp),
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = buildString {
            if (book.author.isNotBlank()) append(book.author)
            if (book.category.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(book.category)
            }
            if (book.updatedAt.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(book.updatedAt)
            }
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    trailing?.invoke()
}

/** Shelf row with reading progress and offline state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(
    book: Book,
    progressText: String?,
    progressFraction: Float?,
    offline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * Replaces the default "已缓存到本机" line, so a caller that knows *how much* is
     * cached can say so instead. Ignored while [offline] is false.
     */
    offlineText: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Long press opens the row's own menu; a plain tap still opens the book.
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .pressHighlight(interactionSource),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookRowContent(book = book, trailing = trailing)
        }
        Column(modifier = Modifier.padding(start = 76.dp, end = 16.dp, bottom = 10.dp)) {
            if (progressText != null) {
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .padding(top = 4.dp),
                )
            }
            if (offline) {
                Text(
                    text = offlineText ?: "已缓存到本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** Centred loading indicator for a whole screen. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 3.dp)
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

private const val WAVE_BAR_COUNT = 5
private const val WAVE_BAR_WIDTH_DP = 3
private const val WAVE_BAR_GAP_DP = 4
private const val WAVE_MAX_HEIGHT_DP = 18
private const val WAVE_MIN_HEIGHT_DP = 4
private const val WAVE_DURATION_MS = 800

/**
 * A compact waveform-style loading indicator for "load more" at the bottom of a list.
 *
 * Draws [WAVE_BAR_COUNT] vertical rounded bars that pulse in a staggered wave pattern
 * from left to right, with "正在加载更多…" text below.
 */
@Composable
fun LoadMoreIndicator(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val barCount = WAVE_BAR_COUNT
    val barWidth = WAVE_BAR_WIDTH_DP.dp
    val gap = WAVE_BAR_GAP_DP.dp
    val barWidthPx: Float
    val gapPx: Float
    val maxHeightPx: Float
    val minHeightPx: Float
    with(LocalDensity.current) {
        barWidthPx = barWidth.toPx()
        gapPx = gap.toPx()
        maxHeightPx = WAVE_MAX_HEIGHT_DP.dp.toPx()
        minHeightPx = WAVE_MIN_HEIGHT_DP.dp.toPx()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val animValues = List(barCount) { index ->
        val delay = index * WAVE_DURATION_MS / barCount / 2
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = InfiniteRepeatableSpec(
                animation = tween(
                    durationMillis = WAVE_DURATION_MS,
                    delayMillis = delay,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar-$index",
        )
    }

    val totalWidth = barWidthPx * barCount + gapPx * (barCount - 1)
    val totalHeight = maxHeightPx

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .width(with(LocalDensity.current) { totalWidth.toDp() })
                .height(with(LocalDensity.current) { totalHeight.toDp() }),
        ) {
            val centerY = size.height / 2f
            for (i in 0 until barCount) {
                val fraction = animValues[i].value
                val barHeight = minHeightPx + (maxHeightPx - minHeightPx) * fraction
                val x = i * (barWidthPx + gapPx)
                val y = centerY - barHeight / 2f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = CornerRadius(barWidthPx / 2f),
                )
            }
        }

        Text(
            text = "正在加载更多…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Empty-state placeholder with an optional action. */
@Composable
fun EmptyBox(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 6.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private val sampleBook = Book(
    bookId = 12345,
    title = "刀剑神域",
    author = "川原砾",
    category = "轻小说",
    latestChapter = "第 28 卷",
    updatedAt = "2026-09-01",
    coverUrl = null,
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BookCardPreview() {
    LightNovelReaderTheme {
        BookCard(book = sampleBook, onClick = {})
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BookRowPreview() {
    LightNovelReaderTheme {
        BookRow(book = sampleBook, onClick = {})
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ShelfRowPreview() {
    LightNovelReaderTheme {
        ShelfRow(
            book = sampleBook,
            progressText = "读到 第 12 章 · 最新：第 28 卷",
            progressFraction = 0.45f,
            offline = true,
            onClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun LoadingBoxPreview() {
    LightNovelReaderTheme {
        LoadingBox(label = "正在加载…")
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun EmptyBoxPreview() {
    LightNovelReaderTheme {
        EmptyBox(
            title = "书架是空的",
            hint = "去搜索你喜欢的轻小说吧",
            actionLabel = "去搜索",
            onAction = {},
        )
    }
}
