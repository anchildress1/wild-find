package dev.anchildress1.wildfind.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.core.sprite.SpriteSheet
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import org.json.JSONObject

/**
 * Plays Briar's [state] sheet once, then loops `idle` until Briar leaves the screen; null plays `idle` alone. With
 * the system animator scale at 0, the sheet holds its first frame.
 *
 * @param cue bump it to replay the same state, e.g. a second find
 */
@Composable
fun Briar(state: BriarState?, description: String, modifier: Modifier = Modifier, cue: Int = 0) {
    var playing by remember(state, cue) { mutableStateOf(state?.sheet ?: BriarState.IDLE) }
    val assets = LocalContext.current.assets
    val sheet = remember(playing) { Loaded(assets, playing) }
    var frame by remember(playing) { mutableIntStateOf(0) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(playing, reduced) {
        // With animations off the state's first frame holds, so Briar still shows how the game reacted.
        if (reduced) return@LaunchedEffect
        val start = withFrameNanos { it }
        var done = false
        while (!done) {
            withFrameNanos { now ->
                frame = sheet.meta.frameAt(now - start)
                done = !sheet.meta.loop && frame == sheet.meta.frames - 1
            }
        }
        playing = BriarState.IDLE
    }
    // A fixed box sized to the largest sheet keeps Briar from jumping when sheets of different cell sizes swap.
    val box = with(LocalDensity.current) { DpSize(BOX_PX.toDp(), BOX_PX.toDp()) }
    Canvas(modifier.size(box).semantics { contentDescription = description }) {
        val cell = IntSize(sheet.meta.frameWidth, sheet.meta.frameHeight)
        val (x, y) = sheet.meta.offsetOf(frame)
        // One sheet pixel per screen pixel: generated frames turn soft when scaled.
        drawImage(
            sheet.image,
            srcOffset = IntOffset(x, y),
            srcSize = cell,
            dstOffset = IntOffset((BOX_PX - cell.width) / 2, BOX_PX - cell.height),
            dstSize = cell,
        )
    }
}

private class Loaded(assets: AssetManager, name: String) {
    val meta: SpriteSheet = JSONObject(assets.open("briar/$name.json").bufferedReader().use { it.readText() }).run {
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

// The largest packed cell (idle and searching, 520 px).
private const val BOX_PX = 520

/** What a screen reader says for Briar in [state]. */
@Composable
fun briarText(state: BriarState?): String = stringResource(
    when (state) {
        null -> R.string.briar_idle
        BriarState.WELCOME -> R.string.briar_welcome
        BriarState.SEARCHING -> R.string.briar_searching
        BriarState.FOUND -> R.string.briar_found
        BriarState.RETRY -> R.string.briar_retry
        BriarState.COMPLETE -> R.string.briar_complete
    },
)
