package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.game.AreaCache
import dev.anchildress1.wildfind.ui.theme.Palette

/**
 * The grown-ups page's Cache my area card: how it works, the button, and the progress line. The button waits while a
 * run is going.
 *
 * @param area the hunting area as it reads on the page, named in the done line
 */
@Composable
fun CacheAreaCard(cache: AreaCache, area: String, onCache: () -> Unit, modifier: Modifier = Modifier) {
    PaperCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(WildIcons.Map, contentDescription = null, Modifier.size(24.dp), tint = Palette.Forest)
            Text(
                stringResource(R.string.cache_title),
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(stringResource(R.string.cache_how), style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(
            stringResource(R.string.cache_button),
            onCache,
            icon = null,
            enabled = cache !is AreaCache.Running,
        )
        Column(
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (cache) {
                AreaCache.Idle -> Unit

                is AreaCache.Running -> Status(stringResource(R.string.cache_running, cache.done + 1, cache.total))

                AreaCache.Done -> Status(stringResource(R.string.cache_done, area))

                is AreaCache.Stopped ->
                    Status(pluralStringResource(R.plurals.cache_stopped, cache.done, cache.done, cache.total))
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2)
}
