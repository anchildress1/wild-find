package dev.anchildress1.wildfind.camera

import androidx.concurrent.futures.CallbackToFutureAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    @Test
    fun focusAndZoomFailuresRemainIndependentUntilTheirOwnRetry() {
        val focus = CameraAdjustmentRequests()
        val zoom = CameraAdjustmentRequests()
        val focusing = Pending<Boolean>()
        val zooming = Pending<Float>()
        focus.track(focusing.future, direct) {}
        zoom.track(zooming.future, direct) {}

        zooming.completer.setException(IllegalStateException("zoom unavailable"))
        focusing.completer.set(true)

        assertTrue(zoom.failed)
        assertFalse(focus.failed)
        val retry = Pending<Float>()
        zoom.track(retry.future, direct) {}
        assertFalse(zoom.failed)
        retry.completer.set(1.5f)
        val nextFocus = Pending<Boolean>()
        focus.track(nextFocus.future, direct) {}
        nextFocus.completer.setException(IllegalStateException("focus unavailable"))
        val nextZoom = Pending<Float>()
        zoom.track(nextZoom.future, direct) {}
        nextZoom.completer.set(2f)

        assertTrue(focus.failed)
        assertFalse(zoom.failed)
    }

    @Test
    fun queuedOlderCompletionCannotChangeTheNewRequestState() {
        val queued = mutableListOf<Runnable>()
        val executor = Executor { queued += it }
        val requests = CameraAdjustmentRequests()
        val first = Pending<Boolean>()
        requests.track(first.future, executor) {}
        first.completer.setException(IllegalStateException("old failure"))
        val next = Pending<Boolean>()
        requests.track(next.future, executor) {}

        queued.removeAt(0).run()
        assertFalse(requests.failed)
        next.completer.set(true)
        queued.removeAt(0).run()
        assertFalse(requests.failed)
    }

    @Test
    fun registrationWaitsUntilAnOlderResultFinishesUpdatingItsState() {
        val requests = CameraAdjustmentRequests()
        val first = Pending<Boolean>()
        val next = Pending<Boolean>()
        val delivering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registered = CountDownLatch(1)
        requests.track(first.future, direct) {
            delivering.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val completion = thread { first.completer.setException(IllegalStateException("old failure")) }
        assertTrue(delivering.await(5, TimeUnit.SECONDS))
        val registration = thread {
            requests.track(next.future, direct) {}
            registered.countDown()
        }
        try {
            assertFalse("new request registered during an old callback", registered.await(200, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            completion.join(5_000)
            registration.join(5_000)
        }
        assertFalse(completion.isAlive)
        assertFalse(registration.isAlive)
        assertFalse(requests.failed)
        next.completer.set(true)
        assertFalse(requests.failed)
    }

    private class Pending<T> {
        lateinit var completer: CallbackToFutureAdapter.Completer<T>
        val future = CallbackToFutureAdapter.getFuture<T> {
            completer = it
            "camera control request"
        }
    }
}
