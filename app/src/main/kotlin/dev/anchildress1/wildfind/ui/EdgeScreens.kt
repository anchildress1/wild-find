package dev.anchildress1.wildfind.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette

/** Launch, before the flags are read. */
@Composable
fun BlankScreen() {
    Box(Modifier.fillMaxSize().background(Palette.Ground))
}

/** The models or the iNat pull are on their way; Briar idles while the line breathes. */
@Composable
fun LoadingScreen() {
    val reduced = LocalReducedMotion.current
    val breath by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 1f,
        targetValue = if (reduced) 1f else DIM,
        animationSpec = infiniteRepeatable(tween(Motion.BIG * 2), RepeatMode.Reverse),
        label = "breath",
    )
    Page(centered = true) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Briar(null, briarText(null)) }
        Text(
            stringResource(R.string.loading),
            Modifier.fillMaxWidth().graphicsLayer { alpha = breath }.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * No answer from iNat and nothing cached here: no hunt starts (R8). The area is already saved, so a grown-up can
 * cache it from the grown-ups page once signal is back.
 */
@Composable
fun NeedsSignalScreen(onRetry: () -> Unit, onArea: () -> Unit, onGrownUps: () -> Unit) {
    Page(
        bottom = {
            PrimaryButton(stringResource(R.string.try_again), onRetry, icon = null)
            OutlineButton(stringResource(R.string.pick_area), onArea)
            LinkButton(stringResource(R.string.needs_signal_grown_ups), onGrownUps, Modifier.rise(index = 1))
        },
    ) {
        TryAgainBriar()
        MessageCard(
            WildIcons.NoSignal,
            stringResource(R.string.needs_signal),
            stringResource(R.string.needs_signal_detail),
            Modifier.rise(),
        )
    }
}

/** The coverage message; never an empty list. */
@Composable
fun NotEnoughScreen(onArea: () -> Unit) {
    Page(bottom = { OutlineButton(stringResource(R.string.pick_area), onArea) }) {
        TryAgainBriar()
        MessageCard(
            WildIcons.Pin,
            stringResource(R.string.not_enough),
            null,
            Modifier.rise(),
        )
    }
}

// The stuck pages: Briar shrugs above the message, so a dead end still has the mascot in it.
@Composable
private fun TryAgainBriar() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Briar(BriarState.TRY_AGAIN, briarText(BriarState.TRY_AGAIN))
    }
}

/** A centered message card for the failure states, with an icon so color never carries the meaning. */
@Composable
private fun MessageCard(icon: ImageVector, title: String, detail: String?, modifier: Modifier = Modifier) {
    PaperCard(modifier) {
        Icon(icon, contentDescription = null, Modifier.size(32.dp), tint = Palette.Forest)
        Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2) }
    }
}

private const val DIM = 0.45f
