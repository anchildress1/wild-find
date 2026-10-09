package dev.anchildress1.wildfind.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.anchildress1.wildfind.core.hunt.PlantType

/** The painted picture for [type] from `assets/plants/`: one per type, never per species, since targets are live. */
@Composable
fun PlantArt(type: PlantType, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val bitmap = remember(type) {
        assets.open("plants/${type.key}.webp").use(BitmapFactory::decodeStream).asImageBitmap()
    }
    Image(bitmap, contentDescription = null, modifier)
}
