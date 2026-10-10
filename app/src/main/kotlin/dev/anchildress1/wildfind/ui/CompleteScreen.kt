package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.game.Stop
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.Palette

/**
 * R15: the hunt's stops show what was found, each find wearing its star over the plant, then Hunt Again or Home; also
 * after Finish hunt. Briar's celebration sits right under the stops, so it shows without scrolling.
 */
@Composable
fun CompleteScreen(stops: List<Stop>, onAgain: () -> Unit, onHome: () -> Unit) {
    val found = stops.count { it.found }
    Page(
        bottom = {
            RuleLine()
            PrimaryButton(stringResource(R.string.hunt_again), onAgain, icon = null)
            OutlineButton(stringResource(R.string.home), onHome)
        },
    ) {
        Text(
            stringResource(R.string.complete_title),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            if (found == stops.size) {
                pluralStringResource(R.plurals.complete_all, found, found)
            } else {
                pluralStringResource(R.plurals.complete_partial, stops.size, found, stops.size)
            },
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stops.forEachIndexed { i, stop ->
                Finished(stop, starIndex = stops.take(i).count { it.found }, Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Briar(BriarState.COMPLETE, briarText(BriarState.COMPLETE))
        }
    }
}

@Composable
private fun Finished(stop: Stop, starIndex: Int, modifier: Modifier) {
    val status = stringResource(if (stop.found) R.string.found_label else R.string.still_out)
    Column(
        modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Found stops wear their star in the middle of the plant: the star takes about a third of the tile's area, the
        // plant the rest. The others fade back, and the status line says it in words.
        val star = stringResource(R.string.one_star)
        Box(contentAlignment = Alignment.Center) {
            TypeTile(stop.type, TILE, Modifier.alpha(if (stop.found) 1f else STILL_OUT_ALPHA))
            if (stop.found) {
                Star(STAR, Modifier.semantics { contentDescription = star }.pop(starIndex))
            }
        }
        // A long name in a narrow card at 200% font breaks mid-word ("mistflowe/r"); a hyphen keeps it readable.
        Text(
            stop.name,
            style = MaterialTheme.typography.titleMedium.copy(hyphens = Hyphens.Auto),
            textAlign = TextAlign.Center,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2)
    }
}

private const val STILL_OUT_ALPHA = 0.45f
private val TILE = 96.dp

// About 59% of the tile's side, a third of its area.
private val STAR = 57.dp
