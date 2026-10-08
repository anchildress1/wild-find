package dev.anchildress1.wildfind.camera

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.wildfind.core.frame.Crops
import java.util.concurrent.Executor

/**
 * The live camera bound to [verifier], with tap-to-focus and pinch zoom as verify rows 3 and 5 tell a kid to use,
 * and the reticle ring drawn by [ring] over the square the models read.
 *
 * @param ring draws the ring at its center and radius in view pixels, once the first frame gives its geometry
 * @param onCamera receives the live camera after each bind
 */
@Composable
fun Viewfinder(
    verifier: CaptureVerifier,
    executor: Executor,
    modifier: Modifier = Modifier,
    onCamera: (Camera) -> Unit = {},
    ring: DrawScope.(center: Offset, radius: Float) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val bound = remember { mutableListOf<UseCase>() }
    val frame by verifier.frameSize.collectAsStateWithLifecycle()
    LaunchedEffect(size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        bindVerifyCamera(
            context, owner, view.display.rotation, size.width, size.height, verifier, executor,
            onSurface = { request = it },
            onBound = { live, useCases ->
                camera = live
                bound += useCases
                onCamera(live)
            },
        )
    }
    // Only this viewfinder's own use cases, so a camera screen entering while this one leaves keeps its binding.
    DisposableEffect(Unit) { onDispose { unbindVerifyCamera(context, bound.toList()) } }
    Box(modifier.onSizeChanged { size = it }) {
        request?.let { Preview(it, camera) }
        frame?.let { (width, height) ->
            val reticle = Crops.reticle(width, height)
            Canvas(Modifier.fillMaxSize()) {
                // Preview and analysis share one viewport, so a single scale maps the frame onto the view.
                val scale = this.size.width / width
                ring(
                    Offset((reticle.left + reticle.width / 2f) * scale, (reticle.top + reticle.height / 2f) * scale),
                    reticle.width / 2f * scale,
                )
            }
        }
    }
}

@Composable
private fun Preview(request: SurfaceRequest, camera: Camera?) {
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
