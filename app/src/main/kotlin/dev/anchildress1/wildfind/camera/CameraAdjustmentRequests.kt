package dev.anchildress1.wildfind.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.common.util.concurrent.ListenableFuture
import dev.anchildress1.wildfind.core.camera.AdjustmentSequence
import java.util.concurrent.Executor

/**
 * Reports only the newest camera adjustment, focus or zoom, since CameraX cancels superseded requests.
 *
 * @param executor the thread that calls [track]; completions run there too, so none can interleave a registration
 */
internal class CameraAdjustmentRequests(private val executor: Executor) {
    private val adjustments = AdjustmentSequence()
    private val zooms = AdjustmentSequence()

    /** Whether the newest adjustment failed. */
    var failed by mutableStateOf(false)
        private set

    /** Tracks [future] as the newest adjustment until a later one supersedes it. */
    fun track(future: ListenableFuture<*>) {
        val token = adjustments.begin()
        failed = false
        future.addListener({
            val failure = runCatching { future.get() }.isFailure
            if (adjustments.settle(token, failure)) failed = failure
        }, executor)
    }

    /**
     * Tracks a zoom; [onFailed] runs when it fails and no newer zoom began, even after a focus took over the error,
     * since a failed zoom leaves the requested ratio unapplied either way.
     */
    fun trackZoom(future: ListenableFuture<*>, onFailed: () -> Unit) {
        val token = zooms.begin()
        future.addListener({
            if (runCatching { future.get() }.isFailure && zooms.settle(token, failure = true)) onFailed()
        }, executor)
        track(future)
    }
}
