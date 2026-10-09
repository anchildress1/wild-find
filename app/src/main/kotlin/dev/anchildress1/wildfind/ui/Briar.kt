package dev.anchildress1.wildfind.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.core.sprite.SpriteSheet
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Plays Briar's [state] once, then loops `idle` until Briar leaves the screen; a looping state such as `complete`
 * keeps looping, and null plays `idle` alone. With the system animator scale at 0, the first frame holds.
 *
 * A state plays its animated WebP when one is packed, else its sprite sheet.
 *
 * @param cue bump it to replay the same state, e.g. a second find
 */
@Composable
fun Briar(state: BriarState?, description: String, modifier: Modifier = Modifier, cue: Int = 0) {
    var playing by remember(state, cue) { mutableStateOf(state?.sheet ?: BriarState.IDLE) }
    val assets = LocalContext.current.assets
    val clip = remember(playing) { assets.list("briar")?.contains("$playing.webp") == true }
    val own = state?.takeIf { it.sheet == playing }
    val rest = own?.replayAfterMillis
    val loop = if (own == null) playing == BriarState.IDLE else own.loop
    val toIdle = { playing = BriarState.IDLE }
    if (clip) {
        Clip(assets, playing, loop, rest, description, modifier, toIdle)
    } else {
        Sheet(assets, playing, rest, description, modifier, toIdle)
    }
}

@Composable
@Suppress("LongParameterList")
private fun Clip(
    assets: AssetManager,
    name: String,
    loop: Boolean,
    rest: Long?,
    description: String,
    modifier: Modifier,
    onDone: () -> Unit,
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
        onDone()
    }
    val size = clip.drawn(LocalDensity.current)
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

@Composable
@Suppress("LongParameterList")
private fun Sheet(
    assets: AssetManager,
    name: String,
    rest: Long?,
    description: String,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    val sheet = remember(name) { Loaded(assets, name) }
    var frame by remember(name) { mutableIntStateOf(0) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(name, reduced) {
        // With animations off the state's first frame holds, so Briar still shows how the game reacted.
        if (reduced) return@LaunchedEffect
        do {
            val start = withFrameNanos { it }
            var done = false
            while (!done) {
                withFrameNanos { now ->
                    frame = sheet.meta.frameAt(now - start)
                    done = !sheet.meta.loop && frame == sheet.meta.frames - 1
                }
            }
        } while (rest?.let { delay(it) } != null)
        onDone()
    }
    // Each source draws Briar at its own size, so every sheet scales until he stands FIGURE tall; a screen keeps
    // one sheet, so the box can follow it.
    val density = LocalDensity.current
    val scale = with(density) { FIGURE.toPx() } / sheet.figureHeight
    val cell = IntSize(sheet.meta.frameWidth, sheet.meta.frameHeight)
    val drawn = IntSize((cell.width * scale).roundToInt(), (cell.height * scale).roundToInt())
    val box = with(density) { DpSize(drawn.width.toDp(), drawn.height.toDp()) }
    Canvas(modifier.size(box).semantics { contentDescription = description }) {
        val (x, y) = sheet.meta.offsetOf(frame)
        drawImage(sheet.image, srcOffset = IntOffset(x, y), srcSize = cell, dstSize = drawn)
    }
}

private fun meta(assets: AssetManager, name: String): JSONObject =
    JSONObject(assets.open("briar/$name.json").bufferedReader().use { it.readText() })

private class LoadedClip(assets: AssetManager, name: String) {
    val figureHeight: Int = meta(assets, name).getInt("figure_height")
    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, "briar/$name.webp"))
        as AnimatedImageDrawable

    /** The drawable's pixel size once Briar is scaled to FIGURE tall. */
    fun drawn(density: Density): IntSize {
        val scale = with(density) { FIGURE.toPx() } / figureHeight
        return IntSize(
            (drawable.intrinsicWidth * scale).roundToInt(),
            (drawable.intrinsicHeight * scale).roundToInt(),
        )
    }
}

private class Loaded(assets: AssetManager, name: String) {
    private val json = meta(assets, name)
    val figureHeight: Int = json.getInt("figure_height")
    val meta: SpriteSheet = json.run {
        SpriteSheet(
            getInt("frame_width"),
            getInt("frame_height"),
            getInt("frames"),
            getInt("columns"),
            getInt("fps"),
            getBoolean("loop"),
        )
    }
    val image: ImageBitmap = assets.open("briar/$name.png").use(BitmapFactory::decodeStream).asImageBitmap()
}

// Briar's height on every screen; idle's art drew him about this tall at one sheet pixel per screen pixel.
private val FIGURE = 168.dp

/** What a screen reader says for Briar in [state]. */
@Composable
fun briarText(state: BriarState?): String = stringResource(
    when (state) {
        null -> R.string.briar_idle
        BriarState.OPENER -> R.string.briar_opener
        BriarState.WELCOME -> R.string.briar_welcome
        BriarState.FOUND -> R.string.briar_found
        BriarState.COMPLETE -> R.string.briar_complete
    },
)
