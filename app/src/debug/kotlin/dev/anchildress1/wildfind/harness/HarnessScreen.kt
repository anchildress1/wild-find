package dev.anchildress1.wildfind.harness

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.wildfind.core.frame.Crops
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private val NO_STATUS = MutableStateFlow(GateStatus())
private val SCRIM = Color.Black.copy(alpha = 0.7f)

/** The harness screen: live viewfinder, the reticle ring the models read, a readout, and the hint button. */
@Composable
fun HarnessScreen(
    runs: StateFlow<GateRun?>,
    requests: StateFlow<SurfaceRequest?>,
    cameras: StateFlow<Camera?>,
    bind: (Int, Int) -> Unit,
) {
    val run by runs.collectAsState()
    val request by requests.collectAsState()
    val camera by cameras.collectAsState()
    val status by (run?.status ?: NO_STATUS).collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(run, size) {
        if (run != null && size != IntSize.Zero) bind(size.width, size.height)
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
        request?.let { Viewfinder(it, camera) }
        Ring(status)
        Readout(status, Modifier.align(Alignment.TopStart))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = { run?.requestCapture() },
                enabled = run != null && !status.capturing,
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Capture", fontSize = 22.sp) }
            Button(
                onClick = { run?.requestHint() },
                enabled = status.gemma == "ready",
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Hint", fontSize = 22.sp) }
        }
    }
}

/** Preview with tap-to-focus and pinch zoom, as verify rows 3 and 4 tell a kid to use. */
@Composable
private fun Viewfinder(request: SurfaceRequest, camera: Camera?) {
    val transformer = remember { MutableCoordinateTransformer() }
    CameraXViewfinder(
        surfaceRequest = request,
        coordinateTransformer = transformer,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(camera, request) {
                detectTapGestures { tap ->
                    val control = camera?.cameraControl ?: return@detectTapGestures
                    val point = with(transformer) { tap.transform() }
                    val factory = SurfaceOrientedMeteringPointFactory(
                        request.resolution.width.toFloat(),
                        request.resolution.height.toFloat(),
                    )
                    control.startFocusAndMetering(
                        FocusMeteringAction.Builder(factory.createPoint(point.x, point.y)).build(),
                    )
                }
            }
            .pointerInput(camera) {
                detectTransformGestures { _, _, zoom, _ ->
                    val live = camera ?: return@detectTransformGestures
                    live.cameraControl.setZoomRatio((live.cameraInfo.zoomState.value?.zoomRatio ?: 1f) * zoom)
                }
            },
    )
}

/** The circle inscribed in the reticle square, mapped from analysis to view coordinates (PRD Crops). */
@Composable
private fun Ring(status: GateStatus) {
    if (status.frameWidth == 0) return
    val reticle = Crops.reticle(status.frameWidth, status.frameHeight)
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Reticle" }) {
        // Preview and analysis share one viewport, so a single scale maps the frame onto the view.
        val scale = size.width / status.frameWidth
        val center = Offset((reticle.left + reticle.width / 2f) * scale, (reticle.top + reticle.height / 2f) * scale)
        val radius = reticle.width / 2f * scale
        drawCircle(Color.Black, radius, center, style = Stroke(width = 8.dp.toPx()))
        drawCircle(Color.White, radius, center, style = Stroke(width = 4.dp.toPx()))
    }
}

@Composable
private fun Readout(status: GateStatus, modifier: Modifier) {
    val verdict = when (val v = status.verdict) {
        null -> "starting"
        is Verdict.Matching -> "matching ${v.frames}/${VerifyStreak.FRAMES}"
        else -> v.toString()
    }
    Column(modifier.fillMaxWidth().background(SCRIM).padding(16.dp)) {
        Text("Find: ${status.target}", color = Color.White, fontSize = 26.sp)
        Text(
            "$verdict · ${status.frameMs.roundToInt()} ms · found ${status.found}",
            color = Color.White,
            fontSize = 22.sp,
        )
        Text(
            "${status.frameWidth}x${status.frameHeight} · PSS ${status.pssMb} MB · thermal ${status.thermalStatus}",
            color = Color.White,
            fontSize = 18.sp,
        )
        if (status.species.isNotEmpty()) Text("sees ${status.species}", color = Color.White, fontSize = 18.sp)
        Text("Gemma ${status.gemma}", color = Color.White, fontSize = 18.sp)
        if (status.hint.isNotEmpty()) Text(status.hint, color = Color.White, fontSize = 18.sp)
    }
}
