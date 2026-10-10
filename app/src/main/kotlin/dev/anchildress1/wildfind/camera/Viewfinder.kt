package dev.anchildress1.wildfind.camera

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.frame.Crops
import dev.anchildress1.wildfind.ui.theme.Palette
import java.util.concurrent.Executor

/**
 * The live camera bound to [verifier], with tap-to-focus and pinch zoom as verify rows 3 and 5 tell a kid to use,
 * and the reticle ring drawn by [ring] over the square the models read.
 *
 * @param ring draws the ring at its center and radius in view pixels, once the first frame gives its geometry
 * @param onCamera receives the live camera after each bind
 */
@Composable
@Suppress("LongParameterList")
fun Viewfinder(
    verifier: CaptureVerifier,
    executor: Executor,
    modifier: Modifier = Modifier,
    onCamera: (Camera) -> Unit = {},
    controls: Boolean = false,
    ring: DrawScope.(center: Offset, radius: Float) -> Unit,
) {
    val generation = remember(verifier) { verifier.attachCamera() }
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val bound = remember { mutableListOf<UseCase>() }
    val pending = remember { arrayOfNulls<PendingBind>(1) }
    val frame by verifier.frameSize.collectAsStateWithLifecycle()
    LaunchedEffect(size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        pending[0]?.cancel()
        pending[0] = bindVerifyCamera(
            context,
            owner,
            ViewGeometry(size.width, size.height, view.display.rotation),
            verifier,
            generation,
            executor,
            onSurface = { request = it },
            onBound = { live, useCases ->
                verifier.resume(generation)
                camera = live
                bound += useCases
                onCamera(live)
            },
        )
    }
    CameraLifecycle(camera, owner, verifier, generation)
    // Only this viewfinder's own use cases, so a camera screen entering while this one leaves keeps its binding.
    DisposableEffect(Unit) {
        onDispose {
            // The provider can answer after the kid has left; a cancelled bind never starts the camera.
            pending[0]?.cancel()
            verifier.unavailable(generation)
            unbindVerifyCamera(context, bound.toList())
        }
    }
    Box(modifier.testTag("camera_viewport").onSizeChanged { size = it }) {
        request?.let { Preview(it, camera, context.mainExecutor, controls) }
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
private fun CameraLifecycle(camera: Camera?, owner: LifecycleOwner, verifier: CaptureVerifier, generation: Long) {
    DisposableEffect(camera, owner) {
        val live = camera
        val watch = Observer<CameraState> {
            if (it.type == CameraState.Type.OPEN && it.error == null) {
                verifier.resume(generation)
            } else {
                verifier.unavailable(generation, failure = it.error != null)
            }
        }
        val lifecycleWatch = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> verifier.resume(generation)
                Lifecycle.Event.ON_STOP -> verifier.unavailable(generation)
                else -> Unit
            }
        }
        live?.cameraInfo?.cameraState?.observe(owner, watch)
        owner.lifecycle.addObserver(lifecycleWatch)
        onDispose {
            live?.cameraInfo?.cameraState?.removeObserver(watch)
            owner.lifecycle.removeObserver(lifecycleWatch)
        }
    }
}

@Composable
private fun Preview(request: SurfaceRequest, camera: Camera?, executor: Executor, controls: Boolean) {
    val transformer = remember { MutableCoordinateTransformer() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(camera) { mutableStateOf<ZoomState?>(null) }
    var error by remember(camera) { mutableStateOf(false) }
    val focusRequests = remember(camera) { CameraAdjustmentRequests() }
    val zoomRequests = remember(camera) { CameraAdjustmentRequests() }
    var requested by remember(camera) { mutableStateOf<Float?>(null) }
    DisposableEffect(camera) {
        val state = camera?.cameraInfo?.zoomState
        val watch = Observer<ZoomState> { zoom = it }
        state?.observeForever(watch)
        onDispose { state?.removeObserver(watch) }
    }
    fun focus(tap: Offset) {
        error = false
        val live = camera ?: return
        val point = with(transformer) { tap.transform() }
        val factory = SurfaceOrientedMeteringPointFactory(
            request.resolution.width.toFloat(),
            request.resolution.height.toFloat(),
        )
        val future = live.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(factory.createPoint(point.x, point.y)).build(),
        )
        focusRequests.track(future, executor) {
            error = it.map { result -> !result.isFocusSuccessful }.getOrDefault(true)
        }
    }
    fun changeZoom(ratio: Float) {
        error = false
        val live = camera ?: return
        val state = zoom ?: return
        val value = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        requested = value
        val future = live.cameraControl.setZoomRatio(value)
        zoomRequests.track(future, executor) { result ->
            if (result.isFailure) {
                requested = null
                error = true
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        CameraXViewfinder(
            surfaceRequest = request,
            coordinateTransformer = transformer,
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }
                .pointerInput(camera, request) { detectTapGestures { focus(it) } }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, scale, _ ->
                        changeZoom((requested ?: zoom?.zoomRatio ?: 1f) * scale)
                    }
                },
        )
        if (controls) {
            Box(Modifier.align(Alignment.TopCenter)) {
                CameraControls(
                    zoom,
                    error,
                    camera != null && viewSize != IntSize.Zero,
                    { focus(Offset(viewSize.width / 2f, viewSize.height / 2f)) },
                    { changeZoom((requested ?: zoom?.zoomRatio ?: 1f) * it) },
                )
            }
        }
    }
}

@Composable
private fun CameraControls(
    zoom: ZoomState?,
    error: Boolean,
    enabled: Boolean,
    onFocus: () -> Unit,
    onZoom: (Float) -> Unit,
) {
    Column(Modifier.padding(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onFocus,
                Modifier.heightIn(min = 48.dp),
                enabled = enabled,
            ) { Text(stringResource(R.string.focus_circle)) }
            Button(
                { onZoom(1f / ZOOM_STEP) },
                Modifier.heightIn(min = 48.dp),
                enabled = zoom?.let { it.zoomRatio > it.minZoomRatio } == true,
            ) { Text(stringResource(R.string.zoom_out)) }
            Button(
                { onZoom(ZOOM_STEP) },
                Modifier.heightIn(min = 48.dp),
                enabled = zoom?.let { it.zoomRatio < it.maxZoomRatio } == true,
            ) { Text(stringResource(R.string.zoom_in)) }
        }
        Text(
            stringResource(if (error) R.string.camera_control_failed else R.string.zoom, zoom?.zoomRatio ?: 1f),
            Modifier.background(Palette.Scrim, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = Color.White,
        )
    }
}

private const val ZOOM_STEP = 1.25f
