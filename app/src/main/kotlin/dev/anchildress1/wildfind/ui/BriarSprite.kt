package dev.anchildress1.wildfind.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.anchildress1.wildfind.core.sprite.SpriteSheet
import org.json.JSONObject

/** Plays the Briar sprite sheet [name] from `assets/briar/` per the PRD sheet contract. */
@Composable
fun BriarSprite(name: String, description: String, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val sheet = remember(name) {
        JSONObject(assets.open("briar/$name.json").bufferedReader().use { it.readText() }).run {
            SpriteSheet(
                getInt("frame_width"),
                getInt("frame_height"),
                getInt("frames"),
                getInt("columns"),
                getInt("fps"),
                getBoolean("loop"),
            )
        }
    }
    val image = remember(name) { assets.open("briar/$name.png").use(BitmapFactory::decodeStream).asImageBitmap() }
    var frame by remember(name) { mutableIntStateOf(0) }
    LaunchedEffect(sheet) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { frame = sheet.frameAt(it - start) }
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val (x, y) = sheet.offsetOf(frame)
        drawImage(
            image,
            srcOffset = IntOffset(x, y),
            srcSize = IntSize(sheet.frameWidth, sheet.frameHeight),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
