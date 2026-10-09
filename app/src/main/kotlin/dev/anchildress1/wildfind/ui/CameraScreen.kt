package dev.anchildress1.wildfind.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    // 0 shows the camera panel; n shows hint n. A new target starts on the camera panel.
    var hintAt by rememberSaveable(target.row) { mutableIntStateOf(0) }
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
    Column(Modifier.fillMaxSize().background(Palette.Night).container(stopKey(target.row))) {
        TopBar(target, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                granted && verifier != null -> Live(verifier, executor, camera)
                denied -> CameraDenied(Modifier.align(Alignment.Center).padding(20.dp))
            }
            Feedback(target, camera, Modifier.align(Alignment.BottomCenter))
        }
        AnimatedContent(
            hintAt,
            transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK)) },
            label = "panel",
        ) { shown ->
            if (shown > 0) {
                HintPanel(target.hints, shown, onNext = { hintAt = shown + 1 }, onKeepLooking = { hintAt = 0 })
            } else {
                BottomPanel(
                    camera,
                    enabled = granted && camera.ready && verifier != null,
                    practice = target.number == 0,
                    canSkip = target.canSkip,
                    hasHint = target.hints.isNotEmpty(),
                    onCapture,
                    onSkip,
                    onHint = { hintAt = 1 },
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
                Modifier.weight(1f).padding(start = 4.dp),
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
            target.type?.let { PlantArt(it, Modifier.size(24.dp)) }
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
private fun Live(verifier: CaptureVerifier, executor: Executor, camera: CameraState) {
    var live by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    DisposableEffect(live) {
        val state = live?.cameraInfo?.zoomState
        val watch = Observer<androidx.camera.core.ZoomState> { zoom = it.zoomRatio }
        state?.observeForever(watch)
        onDispose { state?.removeObserver(watch) }
    }
    Box(Modifier.fillMaxSize()) {
        Viewfinder(verifier, executor, Modifier.fillMaxSize(), onCamera = { live = it }) { center, radius ->
            drawRing(camera, center, radius)
        }
        Text(
            stringResource(R.string.zoom, zoom),
            Modifier.align(Alignment.TopEnd).padding(16.dp).background(Palette.Scrim, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun Feedback(target: CameraTarget, camera: CameraState, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            camera.cue == CaptureCue.HAZARD,
            enter = fadeIn(tween(Motion.QUICK)) + slideInVertically(tween(Motion.MOVE)) { it / 2 },
            exit = fadeOut(tween(Motion.QUICK)),
        ) { HazardCard() }
        AnimatedContent(
            camera.cue.takeIf { it != CaptureCue.HAZARD && it != CaptureCue.FOUND },
            transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK)) },
            label = "pill",
        ) { cue -> cue?.let { Pill(it, target.name) } }
    }
}

@Composable
private fun Pill(cue: CaptureCue, target: String) {
    val (icon, text) = when (cue) {
        CaptureCue.POINT_AT_PLANT -> WildIcons.Aim to stringResource(R.string.cue_point)
        CaptureCue.PUT_IN_CIRCLE -> WildIcons.Target to stringResource(R.string.cue_circle)
        CaptureCue.TAP_TO_FOCUS -> WildIcons.Focus to stringResource(R.string.cue_focus)
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
private fun HazardCard() {
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }
            .background(Palette.Hazard, CardShape).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Briar(BriarState.WARNING, briarText(BriarState.WARNING), figure = WARNING_FIGURE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The warning sign stays beside Briar: every cue pairs a symbol with its words.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(WildIcons.Warning, contentDescription = null, Modifier.size(28.dp), tint = Color.White)
                Text(stringResource(R.string.hazard), style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            Text(
                stringResource(R.string.hazard_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
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
) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Paper, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
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
            if (camera.checking) {
                CheckingButton(Modifier.weight(1f))
            } else {
                PrimaryButton(
                    stringResource(if (camera.ready) R.string.capture else R.string.getting_ready),
                    onCapture,
                    Modifier.weight(1f).heightIn(min = 64.dp),
                    icon = WildIcons.Camera,
                    enabled = enabled,
                )
            }
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
private fun CheckingButton(modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).background(Palette.Husk, RoundedCornerShape(32.dp))
            .border(2.dp, Palette.Moss, RoundedCornerShape(32.dp))
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
                disabled()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WildIcons.Dots, contentDescription = null, Modifier.size(26.dp), tint = Palette.Forest)
        Text(
            stringResource(R.string.checking),
            Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Forest,
        )
    }
}

@Composable
private fun CameraDenied(modifier: Modifier) {
    val context = LocalContext.current
    PaperCard(modifier) {
        Icon(WildIcons.Camera, contentDescription = null, Modifier.size(32.dp), tint = Palette.Forest)
        Text(stringResource(R.string.camera_needed), style = MaterialTheme.typography.titleSmall)
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

// The Hint button takes about a third of the row beside Capture.
private const val HINT_SHARE = 0.55f

// Briar fits beside the hazard text without pushing the card over the viewfinder.
private val WARNING_FIGURE = 88.dp
