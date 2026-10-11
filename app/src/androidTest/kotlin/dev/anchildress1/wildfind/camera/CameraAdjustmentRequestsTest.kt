package dev.anchildress1.wildfind.camera

import androidx.concurrent.futures.CallbackToFutureAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Camera future cancellation and completion ordering at the asynchronous control boundary. */
class CameraAdjustmentRequestsTest {
    private val requests = CameraAdjustmentRequests { it.run() }
    private var resets = 0

    @Test
    fun cancellationOfASupersededZoomRequestCannotResetTheLatestZoom() {
        val first = Pending<Float>()
        val latest = Pending<Float>()
        requests.trackZoom(first.future) { resets++ }
        requests.trackZoom(latest.future) { resets++ }

        first.future.cancel(false)
        assertEquals(0, resets)
        assertFalse(requests.failed)
        latest.completer.set(1.5f)

        assertEquals(0, resets)
        assertFalse(requests.failed)
    }

    @Test
    fun delayedOlderSuccessCannotHideALatestFocusFailure() {
        val first = Pending<Boolean>()
        val latest = Pending<Boolean>()
        requests.track(first.future)
        requests.track(latest.future)

        latest.completer.setException(IllegalStateException("camera closed"))
        first.completer.set(true)

        assertTrue(requests.failed)
    }

    @Test
    fun anOlderFocusSuccessCannotClearANewerZoomFailure() {
        val focusing = Pending<Boolean>()
        val zooming = Pending<Float>()
        requests.track(focusing.future)
        requests.trackZoom(zooming.future) { resets++ }

        zooming.completer.setException(IllegalStateException("zoom unavailable"))
        focusing.completer.set(true)

        assertTrue(requests.failed)
        assertEquals(1, resets)
    }

    @Test
    fun aZoomFailureAfterANewerFocusStillResetsTheZoomButNotTheError() {
        val zooming = Pending<Float>()
        val focusing = Pending<Boolean>()
        requests.trackZoom(zooming.future) { resets++ }
        requests.track(focusing.future)

        focusing.completer.set(true)
        zooming.completer.setException(IllegalStateException("zoom unavailable"))

        assertFalse(requests.failed)
        assertEquals(1, resets)
    }

    @Test
    fun anOlderZoomFailureAfterANewerZoomLeavesTheNewerRequestAlone() {
        val older = Pending<Float>()
        val newer = Pending<Float>()
        requests.trackZoom(older.future) { resets++ }
        requests.trackZoom(newer.future) { resets++ }

        older.completer.setException(IllegalStateException("superseded"))

        assertEquals(0, resets)
        assertFalse(requests.failed)
    }

    @Test
    fun queuedOlderCompletionCannotChangeTheNewRequestState() {
        val queued = mutableListOf<Runnable>()
        val requests = CameraAdjustmentRequests(Executor { queued += it })
        val first = Pending<Boolean>()
        requests.track(first.future)
        first.completer.setException(IllegalStateException("old failure"))
        val next = Pending<Float>()
        requests.trackZoom(next.future) { resets++ }

        queued.removeAt(0).run()
        assertFalse(requests.failed)
        next.completer.set(1.5f)
        queued.forEach(Runnable::run)
        assertFalse(requests.failed)
        assertEquals(0, resets)
    }

    private class Pending<T> {
        lateinit var completer: CallbackToFutureAdapter.Completer<T>
        val future = CallbackToFutureAdapter.getFuture<T> {
            completer = it
            "camera control request"
        }
    }
}
