package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.Palette

/** No hunt yet: Briar waves, and one button starts a hunt in the saved area. */
@Composable
fun StartScreen(region: RegionKey?, onStart: () -> Unit, onGrownUps: () -> Unit) {
    Page(
        top = { MenuRow(onGrownUps) },
        bottom = {
            PrimaryButton(stringResource(R.string.start_button), onStart)
            RuleLine()
        },
    ) {
        Text(
            stringResource(R.string.start_title),
            Modifier.fillMaxWidth().rise(index = 0),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            regionName(region),
            Modifier.fillMaxWidth().rise(index = 1),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Ink2,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Briar(BriarState.WELCOME, briarText(BriarState.WELCOME))
        }
    }
}

/** R3: the first-ever hunt opens with grass; no hazard check runs on it, and the leave-it rule stays on screen. */
@Composable
fun TutorialScreen(onTry: () -> Unit, onGrownUps: () -> Unit) {
    Page(
        top = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PracticeChip() }
                RoundIconButton(WildIcons.More, stringResource(R.string.grown_ups), onGrownUps)
            }
        },
        bottom = {
            PrimaryButton(stringResource(R.string.try_it), onTry)
            RuleLine()
        },
    ) {
        Text(
            stringResource(R.string.tutorial_title),
            Modifier.fillMaxWidth().rise(index = 0),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Briar(BriarState.WELCOME, briarText(BriarState.WELCOME), Modifier.weight(1f, fill = false))
            Box(
                Modifier.rise(index = 1).size(150.dp, 170.dp).background(Palette.Husk, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(WildIcons.Grass, contentDescription = null, Modifier.size(112.dp), tint = Palette.Forest) }
        }
        Text(
            stringResource(R.string.tutorial_point),
            Modifier.fillMaxWidth().rise(index = 2),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.tutorial_then),
            Modifier.fillMaxWidth().rise(index = 3),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Ink2,
            textAlign = TextAlign.Center,
        )
    }
}

/** The tutorial's label, on its intro and its camera. */
@Composable
fun PracticeChip(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.practice),
        modifier.background(Palette.Husk, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Palette.Forest,
    )
}

/** The top row's grown-ups menu, right-aligned. */
@Composable
fun MenuRow(onGrownUps: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        RoundIconButton(WildIcons.More, stringResource(R.string.grown_ups), onGrownUps)
    }
}
