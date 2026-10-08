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
    private class Pending(val goal: Goal, val onFrame: (CapturedFrame) -> Unit) {
        val streak = VerifyStreak()
        var left = VerifyStreak.FRAMES
    }

    private val focus = FocusTrack()
    private val size = MutableStateFlow<FrameSize?>(null)

    @Volatile private var pending: Pending? = null

    /** The sensor's active-array width, for the crop-region zoom fallback; set once the camera is bound. */
    @Volatile var activeArrayWidth = 0

    /** The visible frame's upright size, null until the first frame arrives. */
    val frameSize: StateFlow<FrameSize?> = size.asStateFlow()

    /** True while a capture's frames are being verified. */
    val capturing: Boolean get() = pending != null

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
     * Starts one capture for [goal]; [onFrame] gets each verified frame on the analysis thread. False, and nothing
     * starts, while another capture is still running.
     */
    fun capture(goal: Goal, onFrame: (CapturedFrame) -> Unit): Boolean {
        if (pending != null) return false
        pending = Pending(goal, onFrame)
        return true
    }

    /** Drops a running capture, e.g. when its screen leaves; a frame already in flight still reports. */
    fun cancel() {
        pending = null
    }

    /** Verifies [proxy] while a capture is pending; always closes it. */
    fun analyze(proxy: ImageProxy) = proxy.use {
        val plane = proxy.planes[0]
        check(plane.pixelStride == RGBA_BYTES) { "pixel stride ${plane.pixelStride}" }
        val crop = proxy.cropRect
        val rotation = proxy.imageInfo.rotationDegrees
        val frame =
            RgbaFrame(plane.buffer, plane.rowStride, Box(crop.left, crop.top, crop.width(), crop.height()), rotation)
        size.value = FrameSize(frame.width, frame.height)
        val capture = pending ?: return@use
        val received = SystemClock.elapsedRealtimeNanos()
        val sensorNs = proxy.imageInfo.timestamp
        val first = capture.left == VerifyStreak.FRAMES
        val result = verifier.analyze(frame, capture.goal) { focus.at(sensorNs) }
        val verdict = capture.streak.next(result.evidence)
        capture.left = if (verdict is Verdict.Matching) capture.left - 1 else 0
        // Cleared before the callback, so a listener may start the next capture at once.
        if (capture.left == 0) pending = null
        capture.onFrame(
            CapturedFrame(result, verdict, first, rotation, sensorNs, received, SystemClock.elapsedRealtimeNanos()),
        )
    }

    private companion object {
        const val RGBA_BYTES = 4
    }
}
