package dev.anchildress1.wildfind.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.game.CameraState
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette

/**
 * The camera action panel and its bounded hint sheet; [modifier] applies to whichever is showing.
 *
 * @param onActionHeight receives the action panel's height in pixels whenever it is measured
 */
@Composable
@Suppress("LongParameterList")
internal fun CameraPanels(
    target: CameraTarget,
    camera: CameraState,
    enabled: Boolean,
    sheet: Boolean,
    onCapture: () -> Unit,
    onSkip: () -> Unit,
    onHint: () -> Unit,
    onSheet: (Boolean) -> Unit,
    onActionHeight: (Int) -> Unit,
    modifier: Modifier,
) {
    AnimatedContent(
        sheet && target.hintsShown > 0,
        modifier,
        transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK)) },
        label = "panel",
    ) { open ->
        // Measure actions even for a restored open sheet: closing it must not change the camera viewport.
        Layout(content = {
            BottomPanel(
                camera,
                enabled = enabled,
                practice = target.number == 0,
                canSkip = target.canSkip,
                hasHint = target.hints.isNotEmpty(),
                onCapture,
                onSkip,
                modifier = if (open) Modifier.clearAndSetSemantics {} else Modifier,
                onHint = {
                    onHint()
                    onSheet(true)
                },
            )
            if (open) {
                HintPanel(target.hints, target.hintsShown, onNext = onHint, onKeepLooking = { onSheet(false) })
            }
        }) { panels, constraints ->
            val action = panels[0].measure(constraints)
            onActionHeight(action.height)
            val shown = if (open) panels[1].measure(constraints) else action
            layout(shown.width, shown.height) { shown.placeRelative(0, 0) }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun BottomPanel(
    camera: CameraState,
    enabled: Boolean,
    practice: Boolean,
    canSkip: Boolean,
    hasHint: Boolean,
    onCapture: () -> Unit,
    onSkip: () -> Unit,
    onHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().background(Palette.Paper, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasHint) {
                OutlineButton(
                    stringResource(R.string.hint),
                    onHint,
                    Modifier.weight(HINT_SHARE).heightIn(min = 64.dp),
                    icon = WildIcons.Bulb,
                )
            }
            CaptureButton(
                camera,
                enabled,
                onCapture,
                Modifier.weight(if (hasHint) CAPTURE_SHARE else 1f).heightIn(min = 64.dp),
            )
        }
        // Not every target grows everywhere: a skip swaps in the next plant from the hunt's queue. It always shows, but
        // with nothing left that fits it would only reopen the same plant, so it's disabled.
        LinkButton(
            stringResource(if (practice) R.string.skip_practice else R.string.skip_target),
            onSkip,
            enabled = canSkip && !camera.checking,
        )
        RuleLine()
    }
}

@Composable
private fun CaptureButton(camera: CameraState, enabled: Boolean, onCapture: () -> Unit, modifier: Modifier) {
    val selected = when {
        camera.checking -> R.string.checking
        camera.ready -> R.string.capture
        else -> R.string.getting_ready
    }
    Button(
        onCapture,
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        enabled = enabled && !camera.checking,
        border = BorderStroke(2.dp, if (camera.checking) Palette.Moss else Color.Transparent),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Forest,
            contentColor = Color.White,
            disabledContainerColor = Palette.Husk,
            disabledContentColor = if (camera.checking) Palette.Forest else Palette.Ink2,
        ),
    ) {
        // Reserve every label's wrapped height: camera rebinding would reset zoom and interrupt capture.
        Box(contentAlignment = Alignment.Center) {
            listOf(R.string.getting_ready, R.string.capture, R.string.checking).forEach { label ->
                val visible = label == selected
                Row(
                    if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(label),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                    Icon(
                        if (label == R.string.checking) WildIcons.Dots else WildIcons.Camera,
                        contentDescription = null,
                        Modifier.padding(start = 10.dp).size(22.dp),
                    )
                }
            }
        }
    }
}

// Weights are ratios of the row: Hint 45%, Capture 55% (Capture alone fills it). At 35% the "Hint" label wrapped.
private const val HINT_SHARE = 0.45f
private const val CAPTURE_SHARE = 0.55f
