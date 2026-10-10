package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.anchildress1.wildfind.core.hunt.PlantType

/**
 * The painted picture for [type] from `assets/plants/`: one per type, never per species, since targets are live; a
 * plant the build found no type for gets the untyped picture.
 */
@Composable
fun PlantArt(type: PlantType?, modifier: Modifier = Modifier) {
    val bitmap = assetImage("plants/${type?.key ?: UNTYPED}.webp")
    if (bitmap == null) Spacer(modifier) else Image(bitmap, contentDescription = null, modifier)
}

private const val UNTYPED = "plant"
