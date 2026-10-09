package dev.anchildress1.wildfind.ui

import android.content.res.AssetManager
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Plays Briar's [state] until Briar leaves the screen: a looping state such as `complete` repeats with no rest, the
 * others replay after [BriarState.replayAfterMillis], and null loops `idle`. With the system animator scale at 0, the
 * first frame holds.
 *
 * @param figure how tall Briar stands; one height on every full page, smaller only inside a card
 */
@Composable
fun Briar(state: BriarState?, description: String, modifier: Modifier = Modifier, figure: Dp = FIGURE) {
    val name = state?.sheet ?: BriarState.IDLE
    Clip(
        LocalContext.current.assets,
        name,
        state?.loop ?: true,
        state?.replayAfterMillis,
        figure,
        description,
        modifier,
    )
}

@Composable
@Suppress("LongParameterList")
private fun Clip(
    assets: AssetManager,
    name: String,
    loop: Boolean,
    rest: Long?,
    figure: Dp,
    description: String,
    modifier: Modifier,
) {
    val clip = remember(name) { LoadedClip(assets, name) }
    val drawable = clip.drawable
    // The drawable decodes frames on its own thread and only asks to be redrawn; each ask bumps this.
    var frame by remember(name) { mutableIntStateOf(0) }
    // Drawable holds its callback weakly, so the composition keeps the strong reference.
    val callback = remember(name) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit

            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
    }
    DisposableEffect(drawable) {
        drawable.callback = callback
        onDispose {
            drawable.stop()
            drawable.callback = null
        }
    }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(drawable, reduced) {
        if (reduced) return@LaunchedEffect
        if (loop) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
            awaitCancellation()
        }
        drawable.repeatCount = 0
        do {
            drawable.playOnce()
        } while (rest?.let { delay(it) } != null)
    }
    val size = clip.drawn(LocalDensity.current, figure)
    val box = with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
    Canvas(modifier.size(box).semantics { contentDescription = description }) {
        // Reading the frame count here redraws the canvas whenever the drawable has a new frame.
        if (frame >= 0) {
            drawIntoCanvas {
                drawable.setBounds(0, 0, size.width, size.height)
                drawable.draw(it.nativeCanvas)
            }
        }
    }
}

// Starting a finished play-once drawable restarts it from the first frame.
private suspend fun AnimatedImageDrawable.playOnce() = suspendCancellableCoroutine { done ->
    val ended = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) {
            unregisterAnimationCallback(this)
            if (done.isActive) done.resume(Unit)
        }
    }
    registerAnimationCallback(ended)
    done.invokeOnCancellation { unregisterAnimationCallback(ended) }
    start()
}

private fun meta(assets: AssetManager, name: String): JSONObject =
    JSONObject(assets.open("briar/$name.json").bufferedReader().use { it.readText() })

private class LoadedClip(assets: AssetManager, name: String) {
    val figureHeight: Int = meta(assets, name).getInt("figure_height")
    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, "briar/$name.webp"))
        as AnimatedImageDrawable

    /** The drawable's pixel size once Briar is scaled to [figure] tall. */
    fun drawn(density: Density, figure: Dp): IntSize {
        val scale = with(density) { figure.toPx() } / figureHeight
        return IntSize(
            (drawable.intrinsicWidth * scale).roundToInt(),
            (drawable.intrinsicHeight * scale).roundToInt(),
        )
    }
}

// Briar's height on every screen; idle's art drew him about this tall at one sheet pixel per screen pixel.
private val FIGURE = 168.dp

/** What a screen reader says for Briar in [state]. */
@Composable
fun briarText(state: BriarState?): String = stringResource(
    when (state) {
        null -> R.string.briar_idle
        BriarState.OPENER -> R.string.briar_opener
        BriarState.WARNING, BriarState.WELCOME -> R.string.briar_warning
        BriarState.FOUND -> R.string.briar_found
        BriarState.COMPLETE -> R.string.briar_complete
    },
)
