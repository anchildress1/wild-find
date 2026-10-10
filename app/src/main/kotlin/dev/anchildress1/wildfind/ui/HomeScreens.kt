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
import androidx.compose.foundation.shape.CircleShape
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
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.Palette

/** No hunt yet: over the forest, the title art, Briar waves from the ground, and one button starts a hunt. */
@Composable
fun StartScreen(area: String?, onStart: () -> Unit, onGrownUps: () -> Unit) {
    ForestPage(
        // The menu sits over dark leaves in the painting's corner, so it gets a Paper disc of its own.
        top = { MenuRow(onGrownUps, onPainting = true) },
        bottom = {
            PrimaryButton(stringResource(R.string.start_button), onStart)
            // The rule crosses the painting's darkest ground; Forest needs Paper at 80% there to stay past 4.5:1.
            RuleLine(Modifier.background(Palette.Paper.copy(alpha = RULE_SCRIM), CardShape).padding(vertical = 6.dp))
        },
        upper = {
            TitleArt(Modifier.fillMaxWidth(START_TITLE_WIDTH).align(Alignment.CenterHorizontally).rise(index = 0))
            Text(
                stringResource(R.string.start_title),
                Modifier.fillMaxWidth().rise(index = 1),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                area ?: stringResource(R.string.region_near_you),
                Modifier.fillMaxWidth().rise(index = 2),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Ink2,
                textAlign = TextAlign.Center,
            )
        },
        lower = {
            Briar(BriarState.WELCOME, briarText(BriarState.WELCOME), Modifier.align(Alignment.CenterHorizontally))
        },
    )
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
            // No box: the grass stands on the same line as Briar's feet.
            PlantArt(PlantType.GRASS, Modifier.rise(index = 1).size(140.dp))
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

/**
 * The top row's grown-ups menu, right-aligned.
 *
 * @param onPainting the page shows the forest painting, so the button sits on a Paper disc
 */
@Composable
fun MenuRow(onGrownUps: () -> Unit, onPainting: Boolean = false) {
    val disc = if (onPainting) Modifier.background(Palette.Paper.copy(alpha = MENU_SCRIM), CircleShape) else Modifier
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(disc) { RoundIconButton(WildIcons.More, stringResource(R.string.grown_ups), onGrownUps) }
    }
}

// The title art's share of the page width on Ready to hunt.
private const val START_TITLE_WIDTH = 0.65f

// Measured on opener_background.webp over the darkest pixel under each: the Ink menu icon on Paper at 70% is 7.83:1,
// and the Forest rule line on Paper at 80% is 5.74:1 even over pure black (4.38:1 at 70%, too low). The title, "Ready
// to hunt?", and the area line sit on the painting's light center and need none: Ink 10.74:1, Ink2 6.71:1.
private const val MENU_SCRIM = 0.7f
private const val RULE_SCRIM = 0.8f
