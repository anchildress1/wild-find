package dev.anchildress1.wildfind.harness

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import dev.anchildress1.wildfind.core.frame.Box
import dev.anchildress1.wildfind.core.frame.RgbaFrame
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.verify.Focus
import dev.anchildress1.wildfind.core.verify.FocusTrack
import dev.anchildress1.wildfind.core.verify.FrameVerifier
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.TargetGoal
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import dev.anchildress1.wildfind.inference.BundledAssets
import dev.anchildress1.wildfind.inference.ImageEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the harness screen shows.
 *
 * @property frameWidth upright visible analysis frame width, 0 before the first frame
 * @property frameHeight upright visible analysis frame height
 * @property verdict the last frame's verdict
 * @property frameMs the last frame's time from buffer to verdict
 * @property found Found verdicts this run
 * @property pssMb this process's last sampled PSS
 * @property thermalStatus last sampled `PowerManager` thermal status
 * @property species the reticle's top-1 species-table row on the last frame BioCLIP ran, or empty
 * @property capturing true while a capture's frames are being verified
 * @property target the scientific name this run looks for, or [GateRun.TUTORIAL]
 */
data class GateStatus(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val verdict: Verdict? = null,
    val frameMs: Double = 0.0,
    val found: Int = 0,
    val pssMb: Long = 0,
    val thermalStatus: Int = 0,
    val species: String = "",
    val capturing: Boolean = false,
    val target: String = "",
)

/**
 * One gate-harness run: the real verify path on each capture tap, and memory, heat, and battery sampled off the
 * analysis thread, all logged by [log]. Between taps the camera only previews.
 *
 * Construct off the main thread: it loads both ONNX models and the species table.
 *
 * @param target the target's scientific name, or [TUTORIAL] for the grass tutorial goal
 */
class GateRun(private val context: Context, private val target: String, val log: GateLog) : Closeable {
    private val bundled = BundledAssets(context.assets)
    private val table = bundled.speciesTable()
    private val labels = bundled.speciesLabels(table)
    private val species = labels.map { it.scientific }
    private val rowOf = species.withIndex().associate { (row, name) -> name to row }

    // West Georgia's October pull, a debug asset; the hunt's own iNat pull replaces it in the app.
    private val sightings = context.assets.open(LOCAL_SPECIES).bufferedReader().useLines { lines ->
        lines.filterNot { it.isBlank() || it.startsWith("#") }.map { line ->
            line.split('\t').let { Sighting(it[1], it.getOrNull(2)?.ifBlank { null }, it[0].toInt()) }
        }.toList()
    }
    private val local = LocalSpecies(labels, rowOf::get).of(sightings)

    // The hunt's eligible species compete, with the local toxic and hazard species as blockers.
    private val goal: Goal = if (target == TUTORIAL) {
        bundled.labels().tutorialGoal()
    } else {
        TargetGoal(
            table,
            labels.map { it.genus },
            requireNotNull(local.eligible.firstOrNull { species[it.row] == target }) { "$target is not eligible" }.row,
            local.eligible.map { it.row }.toIntArray(),
            local.blockers,
        )
    }
    private val gateEncoder = ImageEncoder(bundled.plantGateModel())
    private val bioclip = ImageEncoder(bundled.bioclipModel())
    private val verifier = FrameVerifier(
        bundled.plantGate(),
        gateEncoder,
        bioclip,
        bundled.hazardCheck(table, labels, sightings.map { it.scientific }.toSet()),
    )
    private val streak = VerifyStreak()
    private val focus = FocusTrack()
    private val sampler = Executors.newSingleThreadScheduledExecutor()
    private val captureLeft = AtomicInteger(0)
    private val state = MutableStateFlow(GateStatus(target = target))

    private var lastFrameNs = 0L

    /** The sensor's active-array width, for the crop-region zoom fallback; set once the camera is bound. */
    @Volatile var activeArrayWidth = 0

    /** Screen state. */
    val status: StateFlow<GateStatus> = state.asStateFlow()

    /** Records every capture result's focus reading by sensor timestamp; attach to the camera session. */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val zoom = Focus.zoomRatio(
                result.get(CaptureResult.CONTROL_ZOOM_RATIO),
                activeArrayWidth,
                result.get(CaptureResult.SCALER_CROP_REGION)?.width(),
            ) ?: return // no zoom means no close-range reading; the frame logs focus_matched=false
            focus.record(
                timestamp,
                Focus(result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.LENS_FOCUS_DISTANCE), zoom),
            )
        }
    }

    /** Starts sampling. */
    fun start() {
        log.event(now(), "start", target)
        log.event(
            now(),
            "local_species",
            "${sightings.count { it.scientific in rowOf }} of ${sightings.size} local names in the species table; " +
                "${local.eligible.size} eligible, ${local.blockers.size} blockers",
        )
        sampler.scheduleWithFixedDelay(::sample, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
    }

    /** Verifies [proxy] while a capture is pending; always closes it. */
    fun analyze(proxy: ImageProxy) = proxy.use {
        if (captureLeft.get() == 0) return@use
        val plane = proxy.planes[0]
        check(plane.pixelStride == RGBA_BYTES) { "pixel stride ${plane.pixelStride}" }
        val crop = proxy.cropRect
        val frame = RgbaFrame(
            plane.buffer,
            plane.rowStride,
            Box(crop.left, crop.top, crop.width(), crop.height()),
            proxy.imageInfo.rotationDegrees,
        )
        verify(frame, proxy.imageInfo.rotationDegrees, proxy.imageInfo.timestamp)
    }

    /**
     * One capture tap: verify the next [VerifyStreak.FRAMES] frames back to back, stopping at the first that breaks
     * the streak, so a Found still needs that many matching frames in a row.
     */
    fun requestCapture() {
        // Main thread only, and the analyzer only lowers the count, so check-then-set can't race another tap. The
        // button state goes first: set after the count, it could land after the analyzer already finished the
        // capture and disable the button for the rest of the run.
        if (captureLeft.get() != 0) return
        state.update { it.copy(capturing = true) }
        log.event(now(), "capture", target)
        captureLeft.set(VerifyStreak.FRAMES)
    }

    private fun verify(frame: RgbaFrame, rotation: Int, sensorNs: Long) {
        val received = now()
        // A capture's first frame has no gap: the time since the last capture is the tester's pause, not cadence.
        val gapMs = if (captureLeft.get() == VerifyStreak.FRAMES) null else ms(received - lastFrameNs)
        lastFrameNs = received
        val result = verifier.analyze(frame, goal) { focus.at(sensorNs) }
        val verdict = streak.next(result.evidence)
        val frameMs = ms(now() - received)
        val reading = result.evidence.focus
        val times = result.times
        val streakFrames = when (verdict) {
            is Verdict.Matching -> verdict.frames
            Verdict.Found -> VerifyStreak.FRAMES
            else -> 0
        }
        val reticleRank = result.reticleRanking
        val fullRank = result.fullRanking
        log.frame(
            received, sensorNs, gapMs, frame.width, frame.height, rotation, name(verdict), streakFrames,
            result.reticleShare, result.fullShare, reticleRank?.hazardRank, fullRank?.hazardRank,
            reticleRank?.let { species[it.topRow] }, reticleRank?.let { species[it.hazardRow] },
            fullRank?.let { species[it.topRow] }, fullRank?.let { species[it.hazardRow] },
            result.goal?.score, result.goal?.rank,
            reading?.afState, reading?.diopters, reading?.zoomRatio, reading != null,
            ms(times.crop), ms(times.resize), ms(times.plantGate), ms(times.bioclip), ms(times.hazard), ms(times.goal),
            ms(times.total), frameMs,
        )
        val left = if (verdict is Verdict.Matching) captureLeft.decrementAndGet() else 0
        captureLeft.set(left)
        state.update {
            it.copy(
                frameWidth = frame.width,
                frameHeight = frame.height,
                verdict = verdict,
                frameMs = frameMs,
                found = it.found + if (verdict == Verdict.Found) 1 else 0,
                species = reticleRank?.let { rank -> species[rank.topRow] } ?: it.species,
                capturing = left > 0,
            )
        }
    }

    /** Stops sampling and releases every model. Call after the camera stops delivering frames. */
    override fun close() {
        sampler.shutdown()
        // A sample stuck under heat can outlast the wait; the log says so instead of hiding it.
        val drained = sampler.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS)
        log.event(now(), if (drained) "stop" else "stop_timeout", target)
        gateEncoder.close()
        bioclip.close()
        // A thread still running would write to a closed log and crash; every row is already flushed, so leaving the
        // files open loses nothing.
        if (drained) log.close()
    }

    // scheduleWithFixedDelay cancels the schedule on the first uncaught exception without a trace, which would
    // end the thermal record mid-run while the screen kept its last values; log it and keep sampling.
    @Suppress("TooGenericExceptionCaught")
    private fun sample() {
        try {
            sampleOnce()
        } catch (e: Exception) {
            log.event(now(), "sample_error", e.toString())
        }
    }

    private fun sampleOnce() {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val pss = Debug.getPss()
        val thermal = power.currentThermalStatus
        // Missing battery extras log empty, never a made-up 0 °C or 100 %.
        log.system(
            now(), pss, memory.availMem / BYTES_PER_KB, memory.lowMemory, thermal,
            power.getThermalHeadroom(HEADROOM_FORECAST_S),
            if (tenths == Int.MIN_VALUE) null else tenths / TENTHS,
            if (level < 0 || scale <= 0) null else level * PERCENT / scale,
            battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }
                ?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 },
        )
        state.update { it.copy(pssMb = pss / BYTES_PER_KB, thermalStatus = thermal) }
    }

    /** Constants for one run. */
    companion object {
        /** The [GateRun] target that selects the grass tutorial goal. */
        const val TUTORIAL = "grass"

        /** System sampling period; `getThermalHeadroom` returns NaN when called more than once a second. */
        const val SAMPLE_MS = 2_000L

        private const val LOCAL_SPECIES = "local_species.tsv"
        private const val RGBA_BYTES = 4
        private const val NANOS_PER_MS = 1_000_000L
        private const val BYTES_PER_KB = 1024
        private const val TENTHS = 10.0
        private const val PERCENT = 100
        private const val HEADROOM_FORECAST_S = 0
        private const val CLOSE_WAIT_S = 30L

        private fun now() = SystemClock.elapsedRealtimeNanos()

        private fun ms(nanos: Long) = nanos.toDouble() / NANOS_PER_MS

        private fun name(verdict: Verdict) = when (verdict) {
            Verdict.Hazard -> "hazard"
            Verdict.NotPlant -> "not_plant"
            Verdict.TapToFocus -> "tap_to_focus"
            Verdict.WalkCloser -> "walk_closer"
            is Verdict.Matching -> "matching"
            Verdict.Found -> "found"
            Verdict.Guide -> "guide"
        }
    }
}
