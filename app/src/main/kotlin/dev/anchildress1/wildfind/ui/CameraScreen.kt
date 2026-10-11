package dev.anchildress1.wildfind.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.camera.CaptureVerifier
import dev.anchildress1.wildfind.camera.Viewfinder
import dev.anchildress1.wildfind.core.game.CameraState
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette
import java.util.concurrent.Executor

/**
 * The live camera: verify runs only on a Capture tap (3 frames); the result pill stays until the next tap and the
 * hazard card until a capture comes back without one. Briar appears on hunt pages only to warn on the hazard card.
 */
@Composable
@Suppress("LongParameterList")
fun CameraScreen(
    target: CameraTarget,
    camera: CameraState,
    verifier: CaptureVerifier?,
    executor: Executor,
    onCapture: () -> Unit,
    onSkip: () -> Unit,
    onHint: () -> Unit,
    onBack: () -> Unit,
    onDismissHazard: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    // Whether the hint sheet is open; how many hints are open is the game's, so Keep looking then Hint shows the next.
    var sheet by rememberSaveable(target.row) { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // The denial card sends the kid's grown-up to Settings; a grant made there must count on return.
    LifecycleResumeEffect(Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            granted = true
            denied = false
        }
        onPauseOrDispose {}
    }
    // Feedback clears whichever panel shows, while the separately measured actions preserve the camera viewport.
    var shownHeight by remember { mutableIntStateOf(0) }
    var actionHeight by remember { mutableIntStateOf(0) }
    val feedbackPadding = with(LocalDensity.current) { shownHeight.toDp() }
    val viewportPadding = with(LocalDensity.current) { actionHeight.toDp() }
    val streaming = verifier?.streaming?.collectAsStateWithLifecycle()?.value == true
    val cameraFailed = verifier?.cameraFailed?.collectAsStateWithLifecycle()?.value == true
    Column(Modifier.fillMaxSize().background(Palette.Night).container(stopKey(target.row))) {
        TopBar(target, onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // A bind before the panel is measured would be thrown away at the padded size.
                granted && verifier != null && actionHeight > 0 ->
                    Live(verifier, executor, camera, Modifier.fillMaxSize().padding(bottom = viewportPadding))

                denied -> CameraDenied(Modifier.align(Alignment.Center).padding(20.dp))
            }
            val panelLimit = maxHeight
            val hazard = camera.cue == CaptureCue.HAZARD
            if (!hazard) {
                Feedback(
                    target,
                    camera,
                    cameraFailed,
                    onDismissHazard,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = feedbackPadding),
                )
            }
            // At large text sizes the warning and its actions need one shared viewport, not competing fixed panels.
            Column(
                Modifier.align(Alignment.BottomCenter).heightIn(max = panelLimit)
                    .then(if (hazard) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                if (hazard) Feedback(target, camera, cameraFailed, onDismissHazard, Modifier.rise())
                CameraPanels(
                    target, camera, granted && camera.ready && streaming, sheet,
                    onCapture, onSkip, onHint, { sheet = it }, { actionHeight = it },
                    Modifier.heightIn(max = panelLimit).testTag("camera_actions")
                        .onSizeChanged { shownHeight = it.height },
                )
            }
        }
    }
}

@Composable
private fun TopBar(target: CameraTarget, onBack: () -> Unit) {
    Column(
        // The bars are hidden, so only the front camera's cutout needs clearing.
        Modifier.fillMaxWidth().background(Palette.Forest).displayCutoutPadding()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(WildIcons.Back, stringResource(R.string.back_to_hunt), onBack, tint = Color.White)
            Text(
                stringResource(R.string.camera_find, target.name),
                Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            if (target.number == 0) {
                PracticeChip()
            } else {
                Text(
                    stringResource(R.string.camera_progress, target.number, target.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
        // It runs the bar's full width, so at 200% font it wraps into a few lines, not a narrow column that eats the
        // viewfinder.
        Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PlantArt(target.type, Modifier.size(24.dp))
            PlantLine(
                target.description,
                target.type,
                Color.White,
                Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Live(verifier: CaptureVerifier, executor: Executor, camera: CameraState, modifier: Modifier) {
    Viewfinder(verifier, executor, modifier, controls = true) { center, radius ->
        drawRing(camera, center, radius)
    }
}

@Composable
private fun Feedback(
    target: CameraTarget,
    camera: CameraState,
    cameraFailed: Boolean,
    onDismissHazard: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            camera.cue == CaptureCue.HAZARD,
            enter = fadeIn(tween(Motion.QUICK)) + slideInVertically(tween(Motion.MOVE)) { it / 2 },
            exit = fadeOut(tween(Motion.QUICK)),
        ) { HazardCard(camera.hazardLine, onDismissHazard) }
        AnimatedContent(
            if (camera.failed || cameraFailed) {
                CaptureCue.CAMERA_FAILED
            } else {
                camera.cue.takeIf { it != CaptureCue.HAZARD && it != CaptureCue.FOUND }
            },
            transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK)) },
            label = "pill",
        ) { cue -> cue?.let { Pill(it, target.name, cameraFailed) } }
    }
}

@Composable
private fun Pill(cue: CaptureCue, target: String, cameraFailed: Boolean) {
    val (icon, text) = when (cue) {
        CaptureCue.POINT_AT_PLANT -> WildIcons.Aim to stringResource(R.string.cue_point)

        CaptureCue.PUT_IN_CIRCLE -> WildIcons.Target to stringResource(R.string.cue_circle)

        CaptureCue.TAP_TO_FOCUS -> WildIcons.Focus to stringResource(R.string.cue_focus)

        CaptureCue.CAMERA_FAILED ->
            WildIcons.Camera to
                stringResource(if (cameraFailed) R.string.camera_unavailable else R.string.capture_failed)

        CaptureCue.GET_CLOSER -> WildIcons.Steps to stringResource(R.string.cue_closer)

        else -> WildIcons.Search to stringResource(R.string.cue_keep, target)
    }
    Row(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            .background(Palette.Scrim, RoundedCornerShape(26.dp))
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(26.dp), tint = Color.White)
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, color = Color.White)
    }
}

@Composable
private fun HazardCard(line: String?, onDismiss: () -> Unit) {
    // The card stays until the kid taps its button or captures again; it never times out on its own.
    Column(
        Modifier.fillMaxWidth().background(Palette.Hazard, CardShape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HazardMessage(line)
        OutlinedButton(
            onDismiss,
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = BorderStroke(2.dp, Color.White),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) {
            Text(stringResource(R.string.hazard_dismiss), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HazardMessage(line: String?) {
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Briar(BriarState.WARNING, briarText(BriarState.WARNING), figure = WARNING_FIGURE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The warning sign stays beside Briar: every cue pairs a symbol with its words.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(WildIcons.Warning, contentDescription = null, Modifier.size(28.dp), tint = Color.White)
                Text(
                    stringResource(R.string.hazard),
                    Modifier.semantics {
                        heading()
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
            }
            line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            Text(
                stringResource(R.string.hazard_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CameraDenied(modifier: Modifier) {
    val context = LocalContext.current
    PaperCard(modifier) {
        Icon(WildIcons.Camera, contentDescription = null, Modifier.size(32.dp), tint = Palette.Forest)
        Text(
            stringResource(R.string.camera_needed),
            Modifier.semantics {
                heading()
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.camera_needed_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink2,
        )
        PrimaryButton(stringResource(R.string.open_settings), {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }, icon = null)
    }
}

// Briar fits beside the hazard text without pushing the card over the viewfinder.
private val WARNING_FIGURE = 88.dp
