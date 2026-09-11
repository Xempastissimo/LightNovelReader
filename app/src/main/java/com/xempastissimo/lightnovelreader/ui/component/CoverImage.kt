package com.xempastissimo.lightnovelreader.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer
import com.xempastissimo.lightnovelreader.ui.theme.LightNovelReaderTheme

/**
 * Cover thumbnail loaded through the app's own [com.xempastissimo.lightnovelreader.data.repo.ImageLoader].
 *
 * A placeholder is drawn while loading and when the source has no cover, so list
 * rows keep a stable height and never jump.
 */
@Composable
fun CoverImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 6,
) {
    val container = LocalAppContainer.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        val loaded = container.imageLoader.load(url, maxWidth = 320)
        if (loaded != null) {
            bitmap = loaded.asImageBitmap()
        } else {
            failed = true
        }
    }

    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            failed -> CoverPlaceholder(title)

            else -> CircularProgressIndicator(
                modifier = Modifier.padding(8.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/** Draws the first character of the title, which is a readable stand-in for a cover. */
@Composable
private fun CoverPlaceholder(title: String) {
    val initial = title.trim().take(1).ifEmpty { "书" }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title.take(6),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, widthDp = 80, heightDp = 110)
@Composable
private fun CoverPlaceholderPreview() {
    LightNovelReaderTheme {
        CoverPlaceholder(title = "刀剑神域")
    }
}
