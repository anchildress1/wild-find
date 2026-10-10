package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * A page over the watercolor forest from `assets/opener_background.webp`: [upper] sits at the top on the painting's
 * light center, and [lower] sits low on its ground band, so Briar stands in the forest. Both scroll together when 200%
 * font or a short screen makes them taller than the viewport; [top] and [bottom] stay pinned.
 *
 * @param top a fixed row above the scrolling content
 * @param bottom the pinned buttons under it
 * @param upper content at the top of the scrolling area
 * @param lower content at its bottom
 */
@Composable
fun ForestPage(
    top: @Composable () -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit,
    upper: @Composable ColumnScope.() -> Unit,
    lower: @Composable ColumnScope.() -> Unit,
) {
    Page(
        top = top,
        bottom = bottom,
        arrangement = Arrangement.SpaceBetween,
        background = {
            // Ground shows until the painting decodes, so the page never flashes dark.
            assetImage(BACKGROUND)?.let {
                Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = upper)
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = lower)
    }
}

private const val BACKGROUND = "opener_background.webp"
