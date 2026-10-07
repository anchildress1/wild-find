package dev.anchildress1.wildfind.harness

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.ai.edge.litertlm.LiteRtLmJniException
import dev.anchildress1.wildfind.core.frame.Box
import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.frame.RgbaFrame
import dev.anchildress1.wildfind.core.hint.HintPrompts
import dev.anchildress1.wildfind.core.verify.Focus
import dev.anchildress1.wildfind.core.verify.FocusTrack
import dev.anchildress1.wildfind.core.verify.FrameVerifier
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import dev.anchildress1.wildfind.download.GemmaDownloadService
import dev.anchildress1.wildfind.hint.GemmaHint
import dev.anchildress1.wildfind.inference.BundledAssets
import dev.anchildress1.wildfind.inference.ImageEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
 * @property gemma Gemma's state: loading, ready, busy, missing, or failed
 * @property hint the last hint reply and its end-to-end latency, or the last hint error
 */
data class GateStatus(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val verdict: Verdict? = null,
    val frameMs: Double = 0.0,
    val found: Int = 0,
    val pssMb: Long = 0,
    val thermalStatus: Int = 0,
    val gemma: String = "loading",
    val hint: String = "",
)

/**
 * One gate-harness run: the real per-frame verify path at about 5 fps, level-2 hints on tap, and memory, heat,
 * and battery sampled off the analysis thread, all logged by [log].
 *
 * Construct off the main thread: it loads both ONNX models and the species table.
 *
 * @param word the target menu word, or [TUTORIAL] for the grass tutorial goal
 */
class GateRun(private val context: Context, private val word: String, val log: GateLog) : Closeable {
    private val bundled = BundledAssets(context.assets)
    private val labels = bundled.labels()

    // Every stand-in menu word competes, as the hunt's targets and locally eligible words will.
    private val goal: Goal = if (word == TUTORIAL) {
        labels.tutorialGoal()
    } else {
        labels.targetGoal(word, labels.words, floor = null, margin = null)
    }
    private val gateEncoder = ImageEncoder(bundled.plantGateModel())
    private val bioclip = ImageEncoder(bundled.bioclipModel())
    private val verifier = FrameVerifier(bundled.plantGate(), gateEncoder, bioclip, bundled.hazardCheck())
    private val streak = VerifyStreak()
    private val focus = FocusTrack()
    private val hintThread = Executors.newSingleThreadExecutor()
    private val sampler = Executors.newSingleThreadScheduledExecutor()
    private val tap = AtomicLong(NO_TAP)
    private val state = MutableStateFlow(GateStatus())

    @Volatile private var gemma: GemmaHint? = null
    private var lastFrameNs = 0L

    // Main-thread state for the hint ladder.
    private var hintLevel = 0
    private var scene: CompletableFuture<Scene>? = null

    /** A finished scene call for the level-1 tap at [tapNs]; [partsMs] is frame copy, JPEG, and scene call. */
    private class Scene(
        val tapNs: Long,
        val doneNs: Long,
        val jpegBytes: Int,
        val reply: String,
        val partsMs: List<Double>,
    )

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

    /** Starts sampling and loads Gemma in the background. */
    fun start() {
        log.event(now(), "start", word)
        sampler.scheduleWithFixedDelay(::sample, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        hintThread.execute(::loadGemma)
    }

    /** Analyzes [proxy] when the last analyzed frame started at least [FRAME_INTERVAL_MS] ago; always closes it. */
    fun analyze(proxy: ImageProxy) = proxy.use {
        val received = now()
        if (lastFrameNs != 0L && received - lastFrameNs < FRAME_INTERVAL_MS * NANOS_PER_MS) return@use
        val gapMs = if (lastFrameNs == 0L) null else ms(received - lastFrameNs)
        lastFrameNs = received
        val plane = proxy.planes[0]
        check(plane.pixelStride == RGBA_BYTES) { "pixel stride ${plane.pixelStride}" }
        val crop = proxy.cropRect
        val rotation = proxy.imageInfo.rotationDegrees
        val frame =
            RgbaFrame(plane.buffer, plane.rowStride, Box(crop.left, crop.top, crop.width(), crop.height()), rotation)
        if (gapMs == null) log.event(received, "analysis_buffer", "${proxy.width}x${proxy.height} crop $crop")
        val sensorNs = proxy.imageInfo.timestamp
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
        log.frame(
            received, sensorNs, gapMs, frame.width, frame.height, rotation, name(verdict), streakFrames,
            result.reticleShare, result.fullShare, result.reticleRanking?.hazardRank, result.fullRanking?.hazardRank,
            result.goal?.score, result.goal?.rank,
            reading?.afState, reading?.diopters, reading?.zoomRatio, reading != null,
            ms(times.crop), ms(times.resize), ms(times.plantGate), ms(times.bioclip), ms(times.hazard), ms(times.goal),
            ms(times.total), frameMs,
        )
        state.update {
            it.copy(
                frameWidth = frame.width,
                frameHeight = frame.height,
                verdict = verdict,
                frameMs = frameMs,
                found = it.found + if (verdict == Verdict.Found) 1 else 0,
            )
        }
        // After the frame's own timing, so a hint tap never inflates a verify measurement; copied before the
        // proxy closes, since the frame wraps the camera's buffer.
        val tapNs = tap.getAndSet(NO_TAP)
        if (tapNs != NO_TAP) {
            val pixels = frame.upright(Box(0, 0, frame.width, frame.height))
            hintThread.execute { prefetchScene(tapNs, pixels) }
        }
    }

    /**
     * One hint tap, levels in order: level 1 shows its card line at once and starts the scene call on the next
     * frame, so by the level-2 tap usually only the short hint call is left; level 3 shows its card line at once.
     */
    fun requestHint() {
        when (hintLevel) {
            0 -> {
                if (gemma == null) return
                val prefetch = CompletableFuture<Scene>()
                scene = prefetch
                hintLevel = 1
                tap.set(now())
                state.update { it.copy(gemma = "level 1", hint = "1: ${STAND_IN_WHERE.getValue(word)}") }
            }

            1 -> {
                val tapNs = now()
                hintLevel = 2
                state.update { it.copy(gemma = "busy") }
                checkNotNull(scene).whenCompleteAsync({ ready, error -> levelTwo(tapNs, ready, error) }, hintThread)
            }

            2 -> if (state.value.gemma != "busy") {
                hintLevel = LAST_LEVEL
                state.update { it.copy(gemma = "level 3", hint = "3: ${STAND_IN_SHAPE.getValue(word)}") }
            }

            // A new ladder, so one run can measure many level-2 taps.
            else -> {
                hintLevel = 0
                state.update { it.copy(gemma = "ready", hint = "") }
            }
        }
    }

    /** Stops sampling and releases every model. Call after the camera stops delivering frames. */
    override fun close() {
        sampler.shutdown()
        hintThread.execute { gemma?.close() }
        hintThread.shutdown()
        // A queued Gemma load plus a hint can outlast the wait under heat; the log says so instead of hiding it.
        val samplerDone = sampler.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS)
        val hintsDone = hintThread.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS)
        val drained = samplerDone && hintsDone
        log.event(now(), if (drained) "stop" else "stop_timeout", word)
        gateEncoder.close()
        bioclip.close()
        // A thread still running would write to a closed log and crash; every row is already flushed, so leaving the
        // files open loses nothing.
        if (drained) log.close()
    }

    // Model checks, engine setup, load, and warm-up can each fail in ways LiteRT-LM doesn't wrap; every one must
    // land in events.csv and on screen, never kill the run.
    @Suppress("TooGenericExceptionCaught")
    private fun loadGemma() {
        var candidate: GemmaHint? = null
        try {
            val model = GemmaDownloadService.readyModel(context)
            if (model == null) {
                log.event(now(), "gemma_missing", "no verified model on the phone; hints off")
                state.update { it.copy(gemma = "missing") }
                return
            }
            val start = now()
            candidate = GemmaHint(model, context.cacheDir).also { it.load() }
            log.event(now(), "gemma_loaded", "${ms(now() - start)} ms, pss ${Debug.getPss()} kB")
            val warmStart = now()
            candidate.warmUp()
            log.event(now(), "gemma_warmed", "${ms(now() - warmStart)} ms")
            gemma = candidate
            state.update { it.copy(gemma = "ready") }
        } catch (e: Exception) {
            candidate?.close()
            log.event(now(), "gemma_error", e.toString())
            state.update { it.copy(gemma = "failed", hint = e.toString()) }
        }
    }

    // Any failure must settle the future: level 2 waits on it, and a pending one would disable hints for the rest of
    // the run. The level-2 handler logs it as hint_error.
    @Suppress("TooGenericExceptionCaught")
    private fun prefetchScene(tapNs: Long, pixels: Pixels) {
        val prefetch = checkNotNull(scene)
        try {
            val model = checkNotNull(gemma) { "level 1 opened without Gemma" }
            val copied = now()
            val jpeg = jpeg(pixels)
            val encoded = now()
            val reply = model.scene(jpeg)
            val done = now()
            prefetch.complete(
                Scene(
                    tapNs,
                    done,
                    jpeg.size,
                    reply,
                    listOf(ms(copied - tapNs), ms(encoded - copied), ms(done - encoded)),
                ),
            )
        } catch (e: Exception) {
            prefetch.completeExceptionally(e)
        }
    }

    // A failed call is logged and the button comes back: one bad reply mustn't end hint load for the whole run.
    private fun levelTwo(tapNs: Long, scene: Scene?, error: Throwable?) {
        val model = checkNotNull(gemma)
        val start = now()
        val tags = scene?.let { HintPrompts.parseTags(it.reply) }.orEmpty()
        val reply = scene?.let {
            try {
                model.hint(HintPrompts.levelTwo(word, STAND_IN_WHERE.getValue(word), tags))
            } catch (e: LiteRtLmJniException) {
                fail("hint_error", e.toString())
                null
            }
        }
        if (scene == null) fail("hint_error", error.toString())
        if (scene == null || reply == null) return
        val done = now()
        val (frameMs, jpegMs, sceneMs) = scene.partsMs
        log.hint(
            tapNs, ms(tapNs - scene.tapNs), frameMs, jpegMs, sceneMs, ms(maxOf(0L, scene.doneNs - tapNs)),
            ms(done - start), ms(done - tapNs), scene.jpegBytes, tags.joinToString(" "), scene.reply, reply,
        )
        state.update { it.copy(gemma = "level 2", hint = "2 (${ms(done - tapNs).toLong()} ms): $reply") }
    }

    private fun fail(event: String, detail: String) {
        log.event(now(), event, detail)
        state.update { it.copy(gemma = "level 2", hint = "2: $detail") }
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
        /** The [GateRun] word that selects the grass tutorial goal. */
        const val TUTORIAL = "grass"

        /** Analysis pacing: about 5 frames a second, as the PRD verifies. */
        const val FRAME_INTERVAL_MS = 200L

        /** System sampling period; `getThermalHeadroom` returns NaN when called more than once a second. */
        const val SAMPLE_MS = 2_000L

        // Stand-in fact-card "where" lines until real cards exist; they only size the hint prompt like a card.
        private val STAND_IN_WHERE = mapOf(
            "oak" to "Oaks grow in yards, parks, and along the woods edge.",
            "pine" to "Pines grow in sunny spots and drop needles on the ground.",
            "clover" to "Clover grows low in sunny lawns.",
            "dandelion" to "Dandelions grow in lawns and cracks in the sidewalk.",
            "fern" to "Ferns like shady, damp spots.",
            TUTORIAL to "Grass grows in lawns and fields.",
        )

        // Stand-in "shape" lines for level 3, sized like a card's.
        private val STAND_IN_SHAPE = mapOf(
            "oak" to "Look for leaves with rounded or pointed lobes along the edges.",
            "pine" to "Look for long, thin needles in little bundles.",
            "clover" to "Look for three round leaves on one stem.",
            "dandelion" to "Look for jagged leaves in a flat circle on the ground.",
            "fern" to "Look for leaves shaped like green feathers.",
            TUTORIAL to "Look for long, thin blades.",
        )

        private const val LAST_LEVEL = 3
        private const val NO_TAP = -1L
        private const val RGBA_BYTES = 4
        private const val NANOS_PER_MS = 1_000_000L
        private const val BYTES_PER_KB = 1024
        private const val TENTHS = 10.0
        private const val PERCENT = 100
        private const val HEADROOM_FORECAST_S = 0
        private const val JPEG_QUALITY = 90
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

        private fun jpeg(pixels: Pixels): ByteArray {
            val bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encode failed" }
            return out.toByteArray()
        }
    }
}
