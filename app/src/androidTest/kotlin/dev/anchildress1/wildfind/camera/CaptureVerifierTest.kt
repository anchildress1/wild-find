package dev.anchildress1.wildfind.camera

import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.WildFindApp
import dev.anchildress1.wildfind.core.game.Outcome
import dev.anchildress1.wildfind.core.game.Screen
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import dev.anchildress1.wildfind.core.verify.FrameVerifier
import dev.anchildress1.wildfind.core.verify.HazardCheck
import dev.anchildress1.wildfind.core.verify.PlantGate
import dev.anchildress1.wildfind.core.verify.TutorialGoal
import dev.anchildress1.wildfind.game.CaptureRun
import dev.anchildress1.wildfind.harness.GateLog
import dev.anchildress1.wildfind.harness.GateRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real analyzer and inference flow with synthetic camera buffers and encoder-boundary failures. */
@RunWith(AndroidJUnit4::class)
class CaptureVerifierTest {
    private val goal = TutorialGoal(FloatMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)), 0, intArrayOf(0, 1))

    @Test
    fun captureNeedsFreshFramesAndCameraLossClosesThePendingRequestOnce() {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        var frames = 0
        var failures = 0
        assertFalse(verifier.capture(goal, { frames++ }, { failures++ }))
        val frame = Frame()
        verifier.analyze(frame.proxy)
        assertTrue(frame.closed)
        assertTrue(verifier.streaming.value)
        assertTrue(verifier.capture(goal, { frames++ }, { failures++ }))
        assertFalse(verifier.capture(goal, { frames++ }, { failures++ }))

        verifier.unavailable()
        verifier.unavailable()
        val stopped = Frame()
        verifier.analyze(stopped.proxy)

        assertTrue(stopped.closed)
        assertFalse(verifier.streaming.value)
        assertFalse(verifier.capturing)
        assertFalse(verifier.capture(goal, { frames++ }, {}))
        assertEquals(0, frames)
        assertEquals(1, failures)
        verifier.resume()
        verifier.analyze(Frame().proxy)
        assertTrue(verifier.capture(goal, { frames++ }, {}))
    }

    @Test
    fun staleFramesRefuseCaptureWithoutAnOutcomeUntilAFreshFrameArrives() = runBlocking {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        val outcomes = mutableListOf<Outcome>()
        val run = CaptureRun(this, outcomes::add)
        verifier.analyze(Frame().proxy)
        assertTrue(verifier.streaming.value)
        delay(2_100)
        assertTrue(verifier.streaming.value)
        try {
            run.start(verifier, goal, Screen.Camera(0), 7)

            assertFalse(verifier.streaming.value)
            assertFalse(verifier.capturing)
            assertTrue(outcomes.isEmpty())
            val fresh = Frame()
            verifier.analyze(fresh.proxy)
            assertTrue(fresh.closed)
            assertTrue(verifier.streaming.value)
            run.start(verifier, goal, Screen.Camera(0), 8)

            assertTrue(verifier.capturing)
            assertEquals(listOf(Outcome.CaptureStarted(8)), outcomes)
        } finally {
            run.cancel()
            verifier.cancel()
        }
    }

    @Test
    fun idleFramesThenAStalledStreamEndsTheRealCaptureAtItsDeadline() = runBlocking {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        verifier.analyze(Frame().proxy)
        val outcomes = mutableListOf<Outcome>()
        val run = CaptureRun(this, outcomes::add, timeoutMs = 20)

        run.start(verifier, goal, Screen.Camera(0), 7)
        assertTrue(verifier.capturing)
        assertEquals(listOf(Outcome.CaptureStarted(7)), outcomes)
        delay(100)

        assertFalse(verifier.capturing)
        assertEquals(listOf(Outcome.CaptureStarted(7), Outcome.CaptureFailed(7)), outcomes)
        verifier.analyze(Frame().proxy)
        assertTrue(verifier.capture(goal, {}, {}))
        verifier.cancel()
    }

    @Test
    fun leavingTheCameraCancelsItsDeadlineWithoutAFailedOutcome() = runBlocking {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        verifier.analyze(Frame().proxy)
        val outcomes = mutableListOf<Outcome>()
        val run = CaptureRun(this, outcomes::add, timeoutMs = 20)
        run.start(verifier, goal, Screen.Camera(0), 7)

        run.cancel()
        verifier.cancel()
        delay(100)

        assertFalse(verifier.capturing)
        assertEquals(listOf(Outcome.CaptureStarted(7)), outcomes)
    }

    @Test
    fun anOldQueuedFinalFrameCannotCancelTheNextCapturesDeadline() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<Outcome>())
        try {
            scope.launch {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val verifier = verifier { floatArrayOf(0f, 1f) }
            verifier.analyze(Frame().proxy)
            val run = CaptureRun(scope, outcomes::add, timeoutMs = 20)
            run.start(verifier, goal, Screen.Camera(0), 1)
            verifier.analyze(Frame().proxy)
            run.start(verifier, goal, Screen.Camera(0), 2)
            assertTrue(verifier.capturing)

            release.countDown()
            delay(200)

            assertTrue(outcomes.any { it is Outcome.Frame && it.session == 1 })
            assertTrue(outcomes.contains(Outcome.CaptureFailed(2)))
            assertFalse(outcomes.contains(Outcome.CaptureFailed(1)))
            assertFalse(verifier.capturing)
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun disposalOfTheOutgoingViewfinderCannotStopItsReplacement() {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        val old = verifier.attachCamera()
        verifier.resume(old)
        verifier.analyze(Frame().proxy)
        val replacement = verifier.attachCamera()
        assertFalse(verifier.ownsCamera(old))
        assertTrue(verifier.ownsCamera(replacement))
        verifier.resume(replacement)
        val stale = Frame()
        verifier.analyze(stale.proxy, old)
        assertTrue(stale.closed)
        assertFalse(verifier.streaming.value)
        verifier.analyze(Frame().proxy, replacement)

        verifier.unavailable(old)
        verifier.resume(old)

        assertTrue(verifier.streaming.value)
        assertTrue(verifier.capture(goal, {}, {}))
        verifier.unavailable(replacement)
        assertFalse(verifier.streaming.value)
        assertFalse(verifier.capturing)
    }

    @Test
    fun bindingFailureDiffersFromStartupAndOnlyTheCurrentStreamClearsIt() {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        val old = verifier.attachCamera()
        assertFalse(verifier.streaming.value)
        assertFalse(verifier.cameraFailed.value)
        verifier.unavailable(old, failure = true)
        assertTrue(verifier.cameraFailed.value)
        verifier.unavailable(old)
        assertTrue(verifier.cameraFailed.value)
        verifier.resume(old)
        verifier.analyze(Frame().proxy, old)
        assertFalse(verifier.cameraFailed.value)

        val replacement = verifier.attachCamera()
        verifier.unavailable(old, failure = true)
        assertFalse(verifier.cameraFailed.value)
        verifier.unavailable(replacement, failure = true)
        assertTrue(verifier.cameraFailed.value)
        verifier.attachCamera()
        assertFalse(verifier.cameraFailed.value)
    }

    @Test
    fun anEncoderFailureClosesTheFrameAndReportsOneFailedCapture() {
        val calls = AtomicInteger()
        val verifier = verifier {
            calls.incrementAndGet()
            error("encoder unavailable")
        }
        verifier.analyze(Frame().proxy)
        var failures = 0
        var frames = 0
        assertTrue(verifier.capture(goal, { frames++ }, { failures++ }))
        val frame = Frame()

        verifier.analyze(frame.proxy)

        assertTrue(frame.closed)
        assertFalse(verifier.capturing)
        assertEquals(1, failures)
        assertEquals(0, frames)
        assertEquals(1, calls.get())
    }

    @Test
    fun aCancelledFramesEncoderFailureCannotCancelTheNextCapture() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val verifier = verifier {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            error("old encoder failure")
        }
        verifier.analyze(Frame().proxy)
        var failures = 0
        assertTrue(verifier.capture(goal, {}, { failures++ }))
        val frame = Frame()
        val analyzer = thread { verifier.analyze(frame.proxy) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        verifier.cancel()
        assertTrue(verifier.capture(goal, {}, { failures++ }))

        release.countDown()
        analyzer.join(5_000)

        assertFalse(analyzer.isAlive)
        assertTrue(frame.closed)
        assertTrue(verifier.capturing)
        assertEquals(0, failures)
        verifier.cancel()
    }

    @Test
    fun cancellationWaitsForAnAlreadyDeliveringFrame() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val verifier = verifier { floatArrayOf(0f, 1f) }
        verifier.analyze(Frame().proxy)
        assertTrue(
            verifier.capture(goal, {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }, {}),
        )
        val analyzer = thread { verifier.analyze(Frame().proxy) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val canceller = thread {
            verifier.cancel()
            cancelled.countDown()
        }
        try {
            assertFalse(
                "cancellation returned while its callback was still running",
                cancelled.await(200, TimeUnit.MILLISECONDS),
            )
        } finally {
            release.countDown()
            analyzer.join(5_000)
            canceller.join(5_000)
        }
        assertFalse(analyzer.isAlive)
        assertFalse(canceller.isAlive)
        assertFalse(verifier.capturing)
    }

    @Test
    fun terminalCallbackCanStartItsReplacementCapture() {
        val verifier = verifier { floatArrayOf(0f, 1f) }
        verifier.analyze(Frame().proxy)
        var restarted = false
        assertTrue(
            verifier.capture(goal, {
                restarted = verifier.capture(goal, {}, {})
            }, {}),
        )

        verifier.analyze(Frame().proxy)

        assertTrue(restarted)
        assertTrue(verifier.capturing)
        verifier.cancel()
    }

    @Test
    fun harnessRefusalsAndFailuresLeaveCaptureAvailableForRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = (context.applicationContext as WildFindApp).graph.models.await()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gate-capture-test-").toFile()
        val run = GateRun(context, models, GateRun.TUTORIAL, GateLog(directory))
        try {
            run.requestCapture()
            assertFalse("a refused capture disabled the harness", run.status.value.capturing)
            for (stop in listOf<() -> Unit>(
                { run.verifier.unavailable() },
                { run.verifier.analyze(Frame(pixelStride = 1).proxy) },
            )) {
                run.verifier.resume()
                run.verifier.analyze(Frame().proxy)
                run.requestCapture()
                assertTrue(run.status.value.capturing)
                stop()
                assertFalse("a failed capture disabled the harness", run.status.value.capturing)
                assertFalse(run.verifier.capturing)
            }
        } finally {
            run.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closedHarnessLogsCannotStrandCaptureOrPublishUnloggedFrames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = (context.applicationContext as WildFindApp).graph.models.await()
        for (closeBeforeRequest in listOf(true, false)) {
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "gate-closed-log-test-").toFile()
            val run = GateRun(context, models, GateRun.TUTORIAL, GateLog(directory))
            try {
                run.verifier.analyze(Frame().proxy)
                if (closeBeforeRequest) run.close()
                val failure = runCatching { run.requestCapture() }.exceptionOrNull()
                assertEquals("a log failure escaped the capture adapter", null, failure)
                if (!closeBeforeRequest) {
                    assertTrue(run.status.value.capturing)
                    run.close()
                    run.verifier.analyze(Frame().proxy)
                }
                assertFalse("log failure stranded the capture button", run.status.value.capturing)
                assertFalse(run.verifier.capturing)
                assertEquals(null, run.status.value.verdict)
                assertEquals(0, run.status.value.found)
            } finally {
                run.verifier.cancel()
                run.log.close()
                directory.deleteRecursively()
            }
        }
    }

    private fun verifier(encode: () -> FloatArray) = CaptureVerifier(
        FrameVerifier(
            PlantGate(
                listOf(PlantGate.Label(true, floatArrayOf(1f, 0f)), PlantGate.Label(false, floatArrayOf(0f, 1f))),
                1f,
            ),
            { encode() },
            { error("nonplant gate must skip BioCLIP") },
            HazardCheck(
                FloatMatrix(1, 2, floatArrayOf(1f, 0f)),
                booleanArrayOf(true),
                booleanArrayOf(true),
                booleanArrayOf(true),
                region = RegionKey(34, -85),
            ),
        ),
    )

    private class Frame(private val pixelStride: Int = 4) {
        var closed = false
        private val plane = boundary<ImageProxy.PlaneProxy> {
            when (it) {
                "getPixelStride" -> pixelStride
                "getRowStride" -> 16
                "getBuffer" -> ByteBuffer.allocateDirect(64)
                else -> error(it)
            }
        }
        private val info = boundary<ImageInfo> {
            when (it) {
                "getRotationDegrees" -> 0
                "getTimestamp" -> 1L
                else -> error(it)
            }
        }
        val proxy = boundary<ImageProxy> {
            when (it) {
                "getPlanes" -> arrayOf(plane)

                "getCropRect" -> Rect(0, 0, 4, 4)

                "getImageInfo" -> info

                "close" -> {
                    closed = true
                    null
                }

                else -> error(it)
            }
        }
    }

    private companion object {
        inline fun <reified T> boundary(crossinline answer: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                answer(method.name)
            } as T
    }
}
