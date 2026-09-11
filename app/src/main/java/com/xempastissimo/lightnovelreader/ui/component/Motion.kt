package com.xempastissimo.lightnovelreader.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The app's motion vocabulary.
 *
 * Every duration the interface animates with lives here, so the whole app moves at
 * one tempo: touch feedback is immediate, things *leaving* the screen get out of the
 * way faster than things *arriving*, and a change that is meant to be watched (the
 * reader's background following a theme switch) is slower still.
 */
object Motion {
    /** Answer to a finger landing on the screen. Past ~150 ms a press feels laggy. */
    const val PRESS_MILLIS = 110

    /** Something arriving: a screen, a panel, the reader's overlay. */
    const val ENTER_MILLIS = 220

    /** Something leaving. Shorter than [ENTER_MILLIS] — exits should not be watched. */
    const val EXIT_MILLIS = 150

    /** A settle meant to be noticed rather than merely tolerated. */
    const val SLOW_MILLIS = 320
}

/**
 * Squeezes an element slightly while the finger is down.
 *
 * Only a [graphicsLayer] is touched, so the element's layout bounds — and therefore
 * everything laid out around it — stay exactly where they were. The press state is
 * read inside the layer block, which keeps the animation in the draw phase and stops
 * a tap from recomposing the row's contents every frame.
 *
 * Used for wide, card-like targets. Full-width rows of text read better with
 * [pressHighlight], where the response covers the whole row instead of shrinking it.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Washes [color] over a row while the finger is down.
 *
 * A tint rather than a scale: a row of text that shrank under the finger would move
 * its own glyphs, and on a list of rows that reads as jitter. Drawn behind the row's
 * content, and the read of the animated alpha is left in the draw phase for the same
 * reason as [pressScale].
 *
 * The default colour is the app-wide press wash; the ripple still lands on top of it.
 */
@Composable
fun Modifier.pressHighlight(
    interactionSource: MutableInteractionSource,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.PRESS_MILLIS),
        label = "pressHighlight",
    )
    return this.drawBehind {
        val value = alpha.value
        if (value > 0f) drawRect(color = color, alpha = value)
    }
}

/**
 * Depth cue for one page inside a pager.
 *
 * [pageOffset] is the page's signed distance from the page the pager is resting on,
 * as a fraction of the viewport: `0` for the visible page, `±1` for a neighbour that
 * is fully off screen. A page passing the middle of the gesture softens briefly, so a
 * swipe reads as turning through a stack rather than as a hard cut.
 *
 * The effect follows `sin(π · d)`: exactly nil both on the page being read and on a
 * neighbour that has finished leaving, strongest half way. That shape is about cost
 * as much as looks — a page held at rest with `alpha < 1` would keep a full-screen
 * compositing layer alive for as long as it stays composed, and with neighbour pages
 * pre-composed that would be three of them for the whole session.
 *
 * The offset is read through a lambda and inside the layer block, so a gesture that
 * moves the pager only re-draws the pages; the (long) text is never recomposed for it.
 */
fun Modifier.pagerPageDepth(pageOffset: () -> Float): Modifier {
    return this.graphicsLayer {
        val distance = abs(pageOffset()).coerceIn(0f, 1f)
        val mid = sin(distance * PI).toFloat()
        alpha = 1f - mid * 0.14f
    }
}

/**
 * Swaps between a screen's mutually exclusive states — loading, error, empty,
 * content — with a fade instead of a cut.
 *
 * The key is deliberately supplied by the caller: keying on the whole UI state would
 * fade the screen on every keystroke, while keying on the phase (plus, for tabbed
 * screens, the selected tab) fades exactly when the visible content really changed.
 */
@Composable
fun <T> StateCrossfade(
    targetState: T,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = tween(durationMillis = Motion.ENTER_MILLIS),
        label = label,
        content = content,
    )
}
