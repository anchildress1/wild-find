package dev.anchildress1.wildfind.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Reports only the newest camera adjustment, since CameraX cancels superseded requests. */
internal class CameraAdjustmentRequests {
    private var latest: ListenableFuture<*>? = null

    /** Whether this operation's newest request failed; another operation cannot clear it. */
    var failed by mutableStateOf(false)
        private set

    /** Delivers [future]'s result on [executor] while it remains the newest adjustment. */
    @Synchronized
    fun <T> track(future: ListenableFuture<T>, executor: Executor, onComplete: (Result<T>) -> Unit) {
        latest = future
        failed = false
        future.addListener({
            synchronized(this) {
                if (latest === future) {
                    val result = runCatching { future.get() }
                    failed = result.isFailure
                    onComplete(result)
                }
            }
        }, executor)
    }
}
