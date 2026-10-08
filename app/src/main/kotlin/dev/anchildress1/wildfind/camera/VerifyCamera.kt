package dev.anchildress1.wildfind.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Rational
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import dev.anchildress1.wildfind.core.frame.Crops
import java.util.concurrent.Executor

/**
 * Binds the back camera's preview and analysis to one [viewWidth] x [viewHeight] viewport, so the models read exactly
 * what the kid sees and the ring sits on the scored reticle square (PRD Crops). Replaces any earlier binding; call
 * again with the new size after a resize or rotation.
 *
 * @param rotation the display's current `Surface.ROTATION_*`
 * @param executor the analysis thread; [CaptureVerifier.analyze] runs on it
 * @param onSurface receives the preview's surface request
 * @param onBound receives the live camera and the use cases bound for it, on the main thread
 */
@OptIn(ExperimentalCamera2Interop::class)
@Suppress("LongParameterList")
fun bindVerifyCamera(
    context: Context,
    owner: LifecycleOwner,
    rotation: Int,
    viewWidth: Int,
    viewHeight: Int,
    verifier: CaptureVerifier,
    executor: Executor,
    onSurface: (SurfaceRequest) -> Unit,
    onBound: (Camera, List<UseCase>) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val fourByThree = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        // Same aspect as analysis, so the preview's field of view is the analysis frame's.
        val preview = Preview.Builder()
            .setResolutionSelector(fourByThree)
            .setTargetRotation(rotation)
            .build()
            .apply { setSurfaceProvider(onSurface) }
        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        // A phone without the default gets the closest smaller 4:3 size first, which keeps frame cost
                        // bounded.
                        ResolutionStrategy(
                            Size(Crops.ANALYSIS_WIDTH, Crops.ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
        // Capture results carry the autofocus reading; the verifier pairs them with frames by sensor timestamp.
        Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(verifier.captureCallback)
        val analysis = analysisBuilder.build().apply { setAnalyzer(executor, verifier::analyze) }
        val viewPort = ViewPort.Builder(Rational(viewWidth, viewHeight), rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(analysis).build()
        val provider = future.get()
        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        verifier.activeArrayWidth = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.width() ?: 0
        onBound(camera, group.useCases)
    }, context.mainExecutor)
}

/** Releases [useCases] once the screen showing them leaves; a newer binding's use cases stay. */
fun unbindVerifyCamera(context: Context, useCases: List<UseCase>) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({ useCases.forEach { future.get().unbind(it) } }, context.mainExecutor)
}
