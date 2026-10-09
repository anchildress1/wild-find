package dev.anchildress1.wildfind.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// The art set is fixed and small (the star and eight plant pictures), so it stays decoded for the process instead of
// decoding again every time a screen showing it mounts.
private val decoded = ConcurrentHashMap<String, ImageBitmap>()

/** The still image at asset [path], decoded once per process off the main thread; null until that decode finishes. */
@Composable
fun assetImage(path: String): ImageBitmap? {
    val assets = LocalContext.current.assets
    val image by produceState(decoded[path], path) {
        value = decoded[path] ?: withContext(Dispatchers.IO) {
            decoded.getOrPut(path) { assets.open(path).use(BitmapFactory::decodeStream).asImageBitmap() }
        }
    }
    return image
}
