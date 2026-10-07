package dev.anchildress1.wildfind.harness

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import dev.anchildress1.wildfind.core.download.ModelPin
import dev.anchildress1.wildfind.core.frame.Crops
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Debug-only S05 gate harness: the live verify path, level-2 hints, memory, and heat, logged for `make gate-pull`.
 *
 * Launched over adb by `make gate-harness` with the extra [EXTRA_WORD].
 */
class GateHarnessActivity : ComponentActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    private val camera = MutableStateFlow<Camera?>(null)
    private val gateRun = MutableStateFlow<GateRun?>(null)
    private val word by lazy { intent.getStringExtra(EXTRA_WORD) ?: DEFAULT_WORD }
    private val analysisSize = Size(Crops.ANALYSIS_WIDTH, Crops.ANALYSIS_HEIGHT)

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRun()
            } else {
                Log.e(TAG, "camera permission denied")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The 20-minute thermal run must not end because the screen timed out.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { HarnessScreen(gateRun, surfaceRequest, camera, ::bindCamera) }
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // A locked screen stops the camera and lets Android freeze the process; the log must show the gap.
    override fun onStart() {
        super.onStart()
        gateRun.value?.log?.event(SystemClock.elapsedRealtimeNanos(), "resumed", "")
    }

    override fun onStop() {
        gateRun.value?.log?.event(SystemClock.elapsedRealtimeNanos(), "paused", "")
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The run closes on the analysis thread, after any frame in flight, so no model closes mid-frame. A run
        // still loading closes itself when it sees the activity gone.
        synchronized(gateRun) { gateRun.value }?.let { run -> analysisExecutor.execute { run.close() } }
        analysisExecutor.shutdown()
    }

    private fun startRun() {
        val size = analysisSize
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(requireNotNull(getExternalFilesDir(LOG_DIR)), "$stamp-$word")
        check(dir.mkdirs()) { "can't create $dir" }
        // Loading both encoders and the species table takes a second; never on the main thread.
        thread(name = TAG) {
            val log = GateLog(dir)
            log.run(runInfo(word, size))
            val run = GateRun(applicationContext, word, log)
            val alive = synchronized(gateRun) { (!isDestroyed).also { if (it) gateRun.value = run } }
            if (alive) {
                run.start()
                Log.i(TAG, "logging to $dir")
            } else {
                run.close()
            }
        }
    }

    /**
     * Binds preview and analysis to one [viewWidth] x [viewHeight] viewport, so the models read exactly what the
     * kid sees and the ring sits on the scored reticle square. Called again with the new size after a rotation;
     * the run and its log carry on.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(viewWidth: Int, viewHeight: Int) {
        val run = gateRun.value ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val fourByThree = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            // Same aspect as analysis, so the preview's field of view is the analysis frame's.
            val preview = Preview.Builder()
                .setResolutionSelector(fourByThree)
                .setTargetRotation(display.rotation)
                .build()
                .apply { setSurfaceProvider { surfaceRequest.value = it } }
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetRotation(display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            // A phone without the default gets the closest smaller 4:3 size first, which keeps frame
                            // cost bounded; the size it got is logged with every run.
                            ResolutionStrategy(
                                analysisSize,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
            // Capture results carry the autofocus reading; the run pairs them with frames by sensor timestamp.
            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(run.captureCallback)
            val analysis = analysisBuilder.build().apply { setAnalyzer(analysisExecutor, run::analyze) }
            val viewPort = ViewPort.Builder(Rational(viewWidth, viewHeight), display.rotation)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
            val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(analysis).build()
            val provider = future.get()
            provider.unbindAll()
            val live = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
            // 1 = REALTIME: sensor timestamps share elapsedRealtimeNanos' clock, so capture-to-analysis lag is real.
            val info = Camera2CameraInfo.from(live.cameraInfo)
            val source = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            run.activeArrayWidth =
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.width() ?: 0
            run.log.event(
                SystemClock.elapsedRealtimeNanos(),
                "camera",
                "viewport ${viewWidth}x$viewHeight, timestamp_source $source",
            )
            camera.value = live
        }, mainExecutor)
    }

    private fun runInfo(word: String, size: Size) = JSONObject().apply {
        put("started", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("soc", Build.SOC_MODEL)
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("app_version", packageManager.getPackageInfo(packageName, 0).versionName)
        put("word", word)
        put("goal", if (word == GateRun.TUTORIAL) "tutorial" else "target")
        put("requested_analysis", "${size.width}x${size.height}")
        put("frame_interval_ms", GateRun.FRAME_INTERVAL_MS)
        listOf("gemma", "bioclip").forEach { model ->
            put(model, ModelPin.load(model).let { "${it.repo}@${it.revision}/${it.file}" })
        }
    }

    /** Launch extras. */
    companion object {
        /** Target menu word, or `grass` for the tutorial goal. */
        const val EXTRA_WORD = "word"

        private const val TAG = "GateHarness"
        private const val LOG_DIR = "gate"
        private const val DEFAULT_WORD = "oak"
    }
}
