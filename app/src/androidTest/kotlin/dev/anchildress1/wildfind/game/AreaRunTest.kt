package dev.anchildress1.wildfind.game

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.core.game.Command
import dev.anchildress1.wildfind.core.region.RegionKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Cache my area's run bookkeeping: one run at a time, reports in order, and a failure that still ends the run. */
@RunWith(AndroidJUnit4::class)
class AreaRunTest {
    private val home = RegionKey(34, -85)
    private val away = RegionKey(40, -74)

    @Test
    fun aNewRunWaitsForTheOldOneToEndEvenWhenItIsMidPull() = runBlocking {
        val run = AreaRun()
        val midPull = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<RegionKey>()
        val scope = this

        run.on(Command.CacheArea(home), scope) { region ->
            started += region
            // A blocking pull cannot be interrupted, so it finishes after the cancel.
            withContext(NonCancellable) {
                midPull.complete(Unit)
                release.await()
            }
        }
        midPull.await()
        run.on(Command.CacheArea(away), scope) { started += it }
        yield()

        assertEquals("the second run started before the first ended", listOf(home), started)
        release.complete(Unit)
        withTimeout(WAIT_MS) { while (started.size < 2) yield() }
        assertEquals(listOf(home, away), started)
    }

    @Test
    fun cancelCacheAreaStopsTheRunAndStartsNothing() = runBlocking {
        val run = AreaRun()
        val running = CompletableDeferred<Unit>()
        var finished = false

        run.on(Command.CacheArea(home), this) {
            running.complete(Unit)
            delay(Long.MAX_VALUE)
            finished = true
        }
        running.await()
        run.on(Command.CancelCacheArea, this) { error("a cancel must not start a run") }
        yield()

        assertFalse(finished)
    }

    @Test
    fun reportsArriveInOrderAndTheLastOneIsFinished() = runBlocking {
        val reports = mutableListOf<Pair<Int, Boolean>>()

        runAreaCache({ onMonth ->
            (1..3).forEach { onMonth(it) }
            3
        }) { done, finished -> reports += done to finished }

        assertEquals(listOf(1 to false, 2 to false, 3 to false, 3 to true), reports)
    }

    @Test
    fun aFailureMidRunEndsItAsStoppedAtTheMonthsSavedSoFar() = runBlocking {
        val reports = mutableListOf<Pair<Int, Boolean>>()

        runAreaCache({ onMonth ->
            onMonth(1)
            onMonth(2)
            throw IOException("the socket closed")
        }) { done, finished -> reports += done to finished }

        assertEquals(listOf(1 to false, 2 to false, 2 to true), reports)
        assertTrue(reports.last().second)
    }

    @Test
    fun aCancelledRunReportsNothingMore() = runBlocking {
        val reports = mutableListOf<Pair<Int, Boolean>>()
        val running = CompletableDeferred<Unit>()

        val job = launch {
            runAreaCache({ onMonth ->
                onMonth(1)
                running.complete(Unit)
                delay(Long.MAX_VALUE)
                1
            }) { done, finished -> reports += done to finished }
        }
        running.await()
        job.cancelAndJoin()

        assertEquals(listOf(1 to false), reports)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
