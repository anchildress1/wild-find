package dev.anchildress1.wildfind.harness

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import dev.anchildress1.wildfind.WildFindApp
import dev.anchildress1.wildfind.core.download.ModelPin
import dev.anchildress1.wildfind.core.frame.Crops
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Debug-only gate harness: the verify path on each capture, memory, and heat, logged for `make gate-pull`.
 *
 * Launched from its own launcher icon (target water oak) or over adb by `make gate-harness` with the extra
 * [EXTRA_TARGET].
 */
class GateHarnessActivity : ComponentActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val gateRun = MutableStateFlow<GateRun?>(null)
    private val target by lazy { intent.getStringExtra(EXTRA_TARGET) ?: DEFAULT_TARGET }
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
        setContent { HarnessScreen(gateRun, analysisExecutor, ::cameraBound) }
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
        // The run closes on the analysis thread, after any frame in flight, so no frame logs to a closed file. A run
        // still loading closes itself when it sees the activity gone.
        synchronized(gateRun) { gateRun.value }?.let { run -> analysisExecutor.execute { run.close() } }
        analysisExecutor.shutdown()
    }

    // A bad target or a missing asset throws while the run loads; without a caught row the run folder says nothing.
    @Suppress("TooGenericExceptionCaught")
    private fun startRun() {
        val size = analysisSize
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(requireNotNull(getExternalFilesDir(LOG_DIR)), "$stamp-${target.lowercase().replace(' ', '-')}")
        check(dir.mkdirs()) { "can't create $dir" }
        // Waiting for the app's models takes a second at launch; never on the main thread.
        thread(name = TAG) {
            val log = GateLog(dir)
            log.run(runInfo(target, size))
            val run = try {
                val models = runBlocking { (application as WildFindApp).graph.models.await() }
                GateRun(applicationContext, models, target, log)
            } catch (e: Exception) {
                Log.e(TAG, "run failed to load", e)
                log.event(SystemClock.elapsedRealtimeNanos(), "load_error", e.toString())
                log.close()
                runOnUiThread(::finish)
                return@thread
            }
            val alive = synchronized(gateRun) { (!isDestroyed).also { if (it) gateRun.value = run } }
            if (alive) {
                run.start()
                Log.i(TAG, "logging to $dir")
            } else {
                run.close()
            }
        }
    }

    // 1 = REALTIME: sensor timestamps share elapsedRealtimeNanos' clock, so capture-to-analysis lag is real.
    @OptIn(ExperimentalCamera2Interop::class)
    private fun cameraBound(run: GateRun, camera: Camera) {
        val source = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        run.log.event(SystemClock.elapsedRealtimeNanos(), "camera", "timestamp_source $source")
    }

    private fun runInfo(target: String, size: Size) = JSONObject().apply {
        put("started", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("soc", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown")
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("app_version", packageManager.getPackageInfo(packageName, 0).versionName)
        put("target", target)
        put("goal", if (target == GateRun.TUTORIAL) "tutorial" else "target")
        put("requested_analysis", "${size.width}x${size.height}")
        put("capture_frames", VerifyStreak.FRAMES)
        put("bioclip", ModelPin.load("bioclip").let { "${it.repo}@${it.revision}/${it.file}" })
    }

    /** Launch extras. */
    companion object {
        /** Target scientific name, or `grass` for the tutorial goal. */
        const val EXTRA_TARGET = "target"

        private const val TAG = "GateHarness"
        private const val LOG_DIR = "gate"
        private const val DEFAULT_TARGET = "Quercus nigra"
    }
}
