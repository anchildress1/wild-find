package dev.anchildress1.wildfind.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.hunt.Hint
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette

/**
 * The hint sheet over the camera: hint [at] of [hints] (1-based) in large type, earlier ones small above it, Keep
 * looking back to the camera, and the next hint while one is left. The live view keeps running behind it.
 */
@Composable
fun HintPanel(hints: List<Hint>, at: Int, onNext: () -> Unit, onKeepLooking: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Paper, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp)
            .animateContentSize(tween(Motion.MOVE)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(WildIcons.Bulb, contentDescription = null, Modifier.size(24.dp), tint = Palette.Forest)
            Text(
                stringResource(R.string.hint_n_of, at, hints.size),
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        hints.take(at - 1).forEachIndexed { i, hint ->
            Text(
                stringResource(R.string.hint_earlier, i + 1, hint.text),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink2,
            )
        }
        AnimatedContent(
            hints[at - 1],
            transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK)) },
            label = "hint",
        ) { hint ->
            Text(
                hint.text,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        PrimaryButton(stringResource(R.string.keep_looking), onKeepLooking, icon = null)
        if (at < hints.size) {
            OutlineButton(stringResource(R.string.hint_next, at + 1), onNext, icon = WildIcons.Bulb)
        }
        RuleLine()
    }
}
