package dev.anchildress1.wildfind

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

/** Debug-only S09 probe: shows and logs the back camera's live autofocus distance in diopters. */
class FocusProbeActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var readout: TextView
    private var lastLogMs = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Log.e(TAG, "camera permission denied")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        readout = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = READOUT_SP
            setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(preview)
                addView(readout, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
            },
        )
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val builder = Preview.Builder()
            Camera2Interop.Extender(builder).setSessionCaptureCallback(captureCallback)
            val useCase = builder.build().also { it.surfaceProvider = preview.surfaceProvider }
            val provider = future.get()
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
            enableTouch(camera.cameraControl) { camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f }
        }, mainExecutor)
    }

    // Verify row 3 tells the kid to tap to focus, and kids may zoom instead of walking, so the probe measures both.
    @SuppressLint("ClickableViewAccessibility")
    private fun enableTouch(control: CameraControl, zoom: () -> Float) {
        var pinched = false
        val pinch = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    pinched = true
                    control.setZoomRatio(zoom() * detector.scaleFactor)
                    return true
                }
            },
        )
        preview.setOnTouchListener { view, event ->
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinched = false

                MotionEvent.ACTION_UP -> if (!pinched) {
                    control.startFocusAndMetering(
                        FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(event.x, event.y)).build(),
                    )
                    Log.i(TAG, "tap at ${event.x.toInt()},${event.y.toInt()}")
                    view.performClick()
                }
            }
            true
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            // Capture results arrive at the preview frame rate; a few lines a second is enough to read.
            val now = SystemClock.elapsedRealtime()
            if (now - lastLogMs < LOG_EVERY_MS) return
            lastLogMs = now
            val line = "diopters=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} " +
                "af_state=${result.get(CaptureResult.CONTROL_AF_STATE)} " +
                "lens_state=${result.get(CaptureResult.LENS_STATE)} " +
                "zoom=${result.get(CaptureResult.CONTROL_ZOOM_RATIO)} " +
                "physical=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}"
            Log.i(TAG, line)
            runOnUiThread { readout.text = line }
        }
    }

    private companion object {
        const val TAG = "FocusProbe"
        const val LOG_EVERY_MS = 250L
        const val READOUT_SP = 20f
        const val PADDING_PX = 24
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
