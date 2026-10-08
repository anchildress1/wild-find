package dev.anchildress1.wildfind.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.camera.CaptureVerifier
import dev.anchildress1.wildfind.camera.Viewfinder
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.game.CameraState
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette
import java.util.concurrent.Executor

/**
 * The live camera: verify runs only on a Capture tap (3 frames); the result pill stays until the next tap and the
 * hazard card until a capture comes back without one. No Briar on hunt pages.
 */
@Composable
@Suppress("LongParameterList")
fun CameraScreen(
    target: CameraTarget,
    camera: CameraState,
    verifier: CaptureVerifier?,
    executor: Executor,
    onCapture: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
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
    LightStatusIcons()
    Column(Modifier.fillMaxSize().background(Palette.Night).container(stopKey(target.row))) {
        TopBar(target, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                granted && verifier != null -> Live(verifier, executor, camera)
                denied -> CameraDenied(Modifier.align(Alignment.Center).padding(20.dp))
            }
            Feedback(target, camera, Modifier.align(Alignment.BottomCenter))
        }
        BottomPanel(camera, enabled = granted && camera.ready && verifier != null, onCapture)
    }
}

// The top bar is Forest, so the status icons turn light while the camera shows and dark again after.
@Composable
private fun LightStatusIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = true }
    }
}

@Composable
private fun TopBar(target: CameraTarget, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Forest).statusBarsPadding()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(WildIcons.Back, stringResource(R.string.back_to_hunt), onBack, tint = Color.White)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                stringResource(R.string.camera_find, target.name),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            typeLabel(target.type)?.let { type ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    target.type?.let { PlantArt(it, Modifier.size(24.dp)) }
                    Text(
                        type.replaceFirstChar { it.titlecase() },
                        Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }
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
    ) {
        Icon(WildIcons.Warning, contentDescription = null, Modifier.size(30.dp), tint = Color.White)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.hazard), style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                stringResource(R.string.hazard_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun BottomPanel(camera: CameraState, enabled: Boolean, onCapture: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Paper, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (camera.checking) {
            CheckingButton()
        } else {
            PrimaryButton(
                stringResource(if (camera.ready) R.string.capture else R.string.getting_ready),
                onCapture,
                Modifier.heightIn(min = 64.dp),
                icon = WildIcons.Camera,
                enabled = enabled,
            )
        }
        RuleLine()
    }
}

@Composable
private fun CheckingButton() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Palette.Husk, RoundedCornerShape(32.dp))
            .semantics { liveRegion = LiveRegionMode.Polite },
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
