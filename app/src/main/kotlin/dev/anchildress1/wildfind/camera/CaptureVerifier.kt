package dev.anchildress1.wildfind.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import dev.anchildress1.wildfind.core.frame.Box
import dev.anchildress1.wildfind.core.frame.RgbaFrame
import dev.anchildress1.wildfind.core.verify.Focus
import dev.anchildress1.wildfind.core.verify.FocusTrack
import dev.anchildress1.wildfind.core.verify.FrameResult
import dev.anchildress1.wildfind.core.verify.FrameVerifier
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Upright size of the visible analysis frame, which the reticle ring is drawn from.
 *
 * @property width upright width in pixels
 * @property height upright height in pixels
 */
data class FrameSize(val width: Int, val height: Int)

/**
 * One verified frame of a capture.
 *
 * @property result everything the frame produced
 * @property verdict its verdict under the capture's streak
 * @property first true for the capture's first frame
 * @property rotation the frame's rotation to upright
 * @property sensorNs the frame's sensor timestamp
 * @property receivedNs when analysis received it, `elapsedRealtimeNanos`
 * @property doneNs when its verdict was ready, `elapsedRealtimeNanos`
 */
data class CapturedFrame(
    val result: FrameResult,
    val verdict: Verdict,
    val first: Boolean,
    val rotation: Int,
    val sensorNs: Long,
    val receivedNs: Long,
    val doneNs: Long,
)

/**
 * The capture loop the PRD Runtime Logic describes: between taps frames are dropped and the models idle; a tap
 * verifies up to [VerifyStreak.FRAMES] frames back to back, stopping at the first that breaks the streak.
 *
 * Attach [analyze] as the camera's analyzer and [captureCallback] to its session, so every frame gets its own
 * autofocus reading. Shared by the game and the debug gate harness.
 *
 * @param verifier the per-frame model path
 */
class CaptureVerifier(private val verifier: FrameVerifier) {
    private class Pending(val goal: Goal, val onFrame: (CapturedFrame) -> Unit, val onFailure: () -> Unit) {
        val streak = VerifyStreak()
        var left = VerifyStreak.FRAMES
    }

    private val focus = FocusTrack()
    private val size = MutableStateFlow<FrameSize?>(null)

    private val pending = AtomicReference<Pending?>(null)
    private val available = MutableStateFlow(false)
    private val problem = MutableStateFlow(false)
    private val cameraGeneration = AtomicLong()

    /** True while [generation] owns the active camera binding. */
    fun ownsCamera(generation: Long): Boolean = generation == cameraGeneration.get()

    /** Gives a new viewfinder sole ownership of readiness while screen transitions overlap. */
    @Synchronized
    fun attachCamera(): Long {
        unavailable()
        problem.value = false
        return cameraGeneration.incrementAndGet()
    }

    @Volatile private var lastFrameNs = 0L

    @Volatile private var accepting = true

    /** Allows frames from the resumed camera to establish readiness again. */
    @Synchronized
    fun resume(generation: Long = cameraGeneration.get()) {
        if (generation != cameraGeneration.get()) return
        accepting = true
    }

    /** True once the active camera supplies analysis frames. */
    val streaming: StateFlow<Boolean> = available.asStateFlow()

    /** True when camera binding failed, distinct from ordinary startup without frames. */
    val cameraFailed: StateFlow<Boolean> = problem.asStateFlow()

    /** Clears readiness and reports a pending capture failure when the camera stops. */
    @Synchronized
    fun unavailable(generation: Long = cameraGeneration.get(), failure: Boolean = false) {
        if (generation != cameraGeneration.get()) return
        accepting = false
        if (failure) problem.value = true
        available.value = false
        lastFrameNs = 0L
        pending.getAndSet(null)?.onFailure?.invoke()
    }

    /** The sensor's active-array width, for the crop-region zoom fallback; set once the camera is bound. */
    @Volatile var activeArrayWidth = 0

    /** The visible frame's upright size, null until the first frame arrives. */
    val frameSize: StateFlow<FrameSize?> = size.asStateFlow()

    /** True while a capture's frames are being verified. */
    val capturing: Boolean get() = pending.get() != null

    /** Records every capture result's focus reading by sensor timestamp; attach to the camera session. */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            // No zoom means no close-range reading; the frame then has no focus and gets "Tap the plant to focus".
            val zoom = Focus.zoomRatio(
                result.get(CaptureResult.CONTROL_ZOOM_RATIO),
                activeArrayWidth,
                result.get(CaptureResult.SCALER_CROP_REGION)?.width(),
            ) ?: return
            focus.record(
                timestamp,
                Focus(result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.LENS_FOCUS_DISTANCE), zoom),
            )
        }
    }

    /**
     * Starts one capture for [goal]; [onFrame] gets each verified frame on the analysis thread and [onFailure] a camera
     * or inference failure. False, and nothing starts, while another capture is still running or frames are stale.
     */
    @Synchronized
    fun capture(goal: Goal, onFrame: (CapturedFrame) -> Unit, onFailure: () -> Unit): Boolean {
        if (!available.value || SystemClock.elapsedRealtimeNanos() - lastFrameNs > FRAME_FRESH_NS) {
            available.value = false
            return false
        }
        return pending.compareAndSet(null, Pending(goal, onFrame, onFailure))
    }

    /** Drops a running capture, e.g. when its screen leaves; cancelled frames never report. */
    @Synchronized
    fun cancel() {
        pending.set(null)
    }

    /** Verifies [proxy] while a capture is pending; always closes it. */
    fun analyze(proxy: ImageProxy, generation: Long = cameraGeneration.get()) {
        val capture = pending.get()
        try {
            analyzeFrame(proxy, capture, generation)
        } catch (_: Exception) {
            synchronized(this) {
                if (capture != null && pending.compareAndSet(capture, null)) capture.onFailure()
            }
        }
    }

    private fun analyzeFrame(proxy: ImageProxy, capture: Pending?, generation: Long) = proxy.use {
        if (!accepting || generation != cameraGeneration.get()) return@use
        val plane = proxy.planes[0]
        check(plane.pixelStride == RGBA_BYTES) { "pixel stride ${plane.pixelStride}" }
        val crop = proxy.cropRect
        val rotation = proxy.imageInfo.rotationDegrees
        val frame =
            RgbaFrame(plane.buffer, plane.rowStride, Box(crop.left, crop.top, crop.width(), crop.height()), rotation)
        if (!receiveFrame(FrameSize(frame.width, frame.height), generation)) return@use
        if (capture == null || pending.get() !== capture) return@use
        val received = SystemClock.elapsedRealtimeNanos()
        val sensorNs = proxy.imageInfo.timestamp
        val first = capture.left == VerifyStreak.FRAMES
        val result = verifier.analyze(frame, capture.goal) { focus.at(sensorNs) }
        val verdict = capture.streak.next(result.evidence)
        capture.left = if (verdict is Verdict.Matching) capture.left - 1 else 0
        deliver(
            capture,
            CapturedFrame(result, verdict, first, rotation, sensorNs, received, SystemClock.elapsedRealtimeNanos()),
        )
    }

    // Cancellation must finish either before delivery starts or after its callback returns, never between them.
    @Synchronized
    private fun deliver(capture: Pending, frame: CapturedFrame) {
        if (pending.get() !== capture) return
        // Cleared before the callback so a listener may start the next capture on the same reentrant monitor.
        if (capture.left == 0) pending.set(null)
        capture.onFrame(frame)
    }

    @Synchronized
    private fun receiveFrame(frame: FrameSize, generation: Long): Boolean {
        if (!accepting || generation != cameraGeneration.get()) return false
        size.value = frame
        lastFrameNs = SystemClock.elapsedRealtimeNanos()
        available.value = true
        problem.value = false
        return true
    }

    private companion object {
        const val RGBA_BYTES = 4
        const val FRAME_FRESH_NS = 2_000_000_000L
    }
}
