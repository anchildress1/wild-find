package dev.anchildress1.wildfind.camera

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Reports only the newest camera adjustment, since CameraX cancels superseded requests. */
internal class CameraAdjustmentRequests {
    private var latest: ListenableFuture<*>? = null

    /** Delivers [future]'s result on [executor] while it remains the newest adjustment. */
    fun <T> track(future: ListenableFuture<T>, executor: Executor, onComplete: (Result<T>) -> Unit) {
        latest = future
        future.addListener({
            if (latest === future) onComplete(runCatching { future.get() })
        }, executor)
    }
}
