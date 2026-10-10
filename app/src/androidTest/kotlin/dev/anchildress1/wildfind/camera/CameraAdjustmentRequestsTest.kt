package dev.anchildress1.wildfind.camera

import androidx.concurrent.futures.CallbackToFutureAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Camera future cancellation and completion ordering at the asynchronous control boundary. */
class CameraAdjustmentRequestsTest {
    private val direct = Executor { it.run() }

    @Test
    fun cancellationOfASupersededZoomRequestCannotResetTheLatestZoom() {
        val requests = CameraAdjustmentRequests()
        val first = Pending<Float>()
        val latest = Pending<Float>()
        val answers = mutableListOf<Result<Float>>()
        requests.track(first.future, direct, answers::add)
        requests.track(latest.future, direct, answers::add)

        first.future.cancel(false)
        assertTrue(answers.isEmpty())
        latest.completer.set(1.5f)

        assertEquals(1, answers.size)
        assertEquals(1.5f, answers.single().getOrThrow())
    }

    @Test
    fun delayedOlderSuccessCannotHideALatestFocusFailure() {
        val requests = CameraAdjustmentRequests()
        val first = Pending<Boolean>()
        val latest = Pending<Boolean>()
        val answers = mutableListOf<Result<Boolean>>()
        requests.track(first.future, direct, answers::add)
        requests.track(latest.future, direct, answers::add)

        latest.completer.setException(IllegalStateException("camera closed"))
        first.completer.set(true)

        assertEquals(1, answers.size)
        assertFalse(answers.single().isSuccess)
        assertEquals("camera closed", answers.single().exceptionOrNull()?.cause?.message)
    }
    private class Pending<T> {
        lateinit var completer: CallbackToFutureAdapter.Completer<T>
        val future = CallbackToFutureAdapter.getFuture<T> {
            completer = it
            "camera control request"
        }
    }
}
