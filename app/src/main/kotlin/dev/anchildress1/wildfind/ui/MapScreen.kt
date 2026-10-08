package dev.anchildress1.wildfind.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.map.Heading
import dev.anchildress1.wildfind.core.map.MapCamera
import dev.anchildress1.wildfind.core.map.Places
import dev.anchildress1.wildfind.core.map.WorldMap
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.game.MapFocus
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val CameraSaver = Saver<MapCamera, List<Double>>(
    save = { listOf(it.lat, it.lng, it.span) },
    restore = { (lat, lng, span) -> MapCamera(lat, lng, span) },
)

/**
 * The built-in map picker: it opens on the whole world, the map pans and zooms under fixed crosshairs, a released
 * drag snaps to the nearest whole degree, and "Hunt here" unlocks once zoomed in to about 12° across. Only the
 * crosshairs' whole-degree spot ever leaves the phone (R2, R7).
 *
 * @param map the built-in map, null while it loads
 * @param places offline state and country names for the label, null while they load
 * @param focus where Locate found the rough location
 * @param canLocate location was never denied, so Locate may ask once
 * @param locating Locate is waiting for the rough location, so "Hunt here" waits too
 */
@Composable
@Suppress("LongParameterList")
fun MapScreen(
    map: WorldMap?,
    places: Places?,
    focus: MapFocus?,
    canLocate: Boolean,
    locating: Boolean,
    onLocation: (granted: Boolean) -> Unit,
    onPick: (RegionKey) -> Unit,
    onBack: () -> Unit,
) {
    var camera by rememberSaveable(stateSaver = CameraSaver) { mutableStateOf(MapCamera()) }
    val glide = rememberGlide({ camera }, { camera = it })
    // A fix can take seconds; once the kid has moved the map, the opening one no longer yanks it away.
    var moved by rememberSaveable { mutableStateOf(false) }
    val steer: (MapCamera) -> Unit = {
        moved = true
        glide(it)
    }
    LaunchedEffect(focus) {
        if (focus != null && !(focus.opening && moved)) {
            glide(camera.copy(span = minOf(camera.span, MapCamera.AREA_SPAN)).at(focus.region))
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
            RoundIconButton(WildIcons.Back, stringResource(R.string.back), onBack)
            Text(
                stringResource(R.string.map_title),
                Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Pannable(map, camera, label(camera, places), {
                moved = true
                camera = it
            }) { glide(camera.at(camera.region)) }
            Crosshairs(Modifier.align(Alignment.Center))
            Guide(camera, places, Modifier.align(Alignment.TopCenter))
            Controls(canLocate, onLocation, Modifier.align(Alignment.TopEnd)) { steer(camera.zoom(it)) }
            Pad(camera.canPick, Modifier.align(Alignment.BottomEnd)) { steer(camera.step(it)) }
        }
        PickPanel(camera.canPick, enabled = !locating) { onPick(camera.region) }
    }
}

// The crosshairs' region as the chip and TalkBack read it: whole degrees, then its state or country when on land.
@Composable
private fun label(camera: MapCamera, places: Places?): String {
    val region = camera.region
    return remember(region, places) { Places.label(region, places?.nameAt(region)) }
}

// The arrow pad shows only once picking unlocks.
@Composable
private fun Pad(visible: Boolean, modifier: Modifier, onStep: (Heading) -> Unit) {
    AnimatedVisibility(
        visible,
        modifier.padding(12.dp),
        enter = fadeIn(tween(Motion.QUICK)),
        exit = fadeOut(tween(Motion.QUICK)),
    ) { ArrowPad(onStep) }
}

// Drags and pinches move the camera directly; lifting the last finger snaps the crosshairs to a whole degree.
@Composable
private fun Pannable(
    map: WorldMap?,
    camera: MapCamera,
    label: String,
    onMove: (MapCamera) -> Unit,
    onRelease: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The gesture detector outlives recompositions, so it reads the newest camera, never the first one it saw.
    val current by rememberUpdatedState(camera)
    val world = stringResource(R.string.map_world_label)
    val area = stringResource(R.string.map_area_label, label)
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .semantics { contentDescription = if (camera.canPick) area else world }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (size.width == 0) return@detectTransformGestures
                    val perPixel = current.span / size.width
                    onMove(current.zoom(zoom.toDouble()).pan(-pan.x * perPixel, pan.y * perPixel))
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                    onRelease()
                }
            },
    ) { MapCanvas(map, camera) }
}

@Composable
private fun Guide(camera: MapCamera, places: Places?, modifier: Modifier) {
    val chip = Modifier.background(
        Palette.Paper,
        RoundedCornerShape(18.dp),
    ).padding(horizontal = 16.dp, vertical = 8.dp)
    Box(modifier.padding(top = 16.dp, start = 72.dp, end = 72.dp)) {
        if (camera.canPick) {
            Text(
                label(camera, places),
                chip,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Forest,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                stringResource(R.string.map_world_hint),
                chip,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PickPanel(canPick: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Paper, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (canPick) R.string.map_place else R.string.map_zoom_more),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
        )
        PrimaryButton(
            stringResource(if (canPick) R.string.map_hunt_here else R.string.map_zoom_to_pick),
            onPick,
            icon = if (canPick) WildIcons.Chevron else null,
            enabled = canPick && enabled,
        )
        PrivateLine(stringResource(R.string.map_private))
    }
}

/**
 * Moves the camera to a target over [Motion.QUICK] (snap, arrows, Locate) or [Motion.MOVE] (zoom buttons); a new
 * glide or a finger cancels the last one, and reduced motion jumps straight there.
 */
@Composable
private fun rememberGlide(current: () -> MapCamera, set: (MapCamera) -> Unit): (MapCamera) -> Unit {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    var job by remember { mutableStateOf<Job?>(null) }
    return { target ->
        job?.cancel()
        val start = current()
        if (reduced) {
            set(target)
        } else {
            val duration = if (target.span != start.span) Motion.MOVE else Motion.QUICK
            job = scope.launch {
                animate(0f, 1f, animationSpec = tween(duration, easing = Motion.Decelerate)) { t, _ ->
                    set(start.toward(target, t.toDouble()))
                }
            }
        }
    }
}
