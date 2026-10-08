package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.game.Stop
import dev.anchildress1.wildfind.ui.theme.Palette

/** R15: the stars pop in, the hunt's stops show what was found, then Hunt Again or Home; also after Finish hunt. */
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
        val stars = pluralStringResource(R.plurals.stars, found, found)
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = stars },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEachIndexed { i, stop ->
                val size = if (i == 1) 76.dp else 68.dp
                if (stop.found) {
                    Star(size, Modifier.pop(i))
                } else {
                    Icon(WildIcons.StarOutline, null, Modifier.size(size), tint = Palette.Line)
                }
            }
        }
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
            stops.forEach { Finished(it, Modifier.weight(1f)) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Briar(BriarState.COMPLETE, briarText(BriarState.COMPLETE))
        }
    }
}

@Composable
private fun Finished(stop: Stop, modifier: Modifier) {
    val status = stringResource(if (stop.found) R.string.found_label else R.string.still_out)
    Column(
        modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypeTile(stop.type, 88.dp)
        // A long name in a narrow card at 200% font breaks mid-word ("mistflowe/r"); a hyphen keeps it readable.
        Text(
            stop.name,
            style = MaterialTheme.typography.titleMedium.copy(hyphens = Hyphens.Auto),
            textAlign = TextAlign.Center,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2)
    }
}
