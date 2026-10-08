package dev.anchildress1.wildfind

import android.app.Application
import dev.anchildress1.wildfind.inat.InatClient
import dev.anchildress1.wildfind.inference.BundledAssets
import dev.anchildress1.wildfind.store.GameStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Holds the process-wide pieces; models start loading at launch so the first capture doesn't wait. */
class WildFindApp : Application() {
    /** The app's dependencies. */
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

/**
 * The app's dependencies, one per process.
 *
 * @param app the application
 */
class Graph(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Flags, the current hunt, and cached pulls; nothing reaches a cloud backup. */
    val store = GameStore(app.noBackupFilesDir)

    /** The only network call. */
    val inat = InatClient()

    /** Coarse location. */
    val location = RoughLocation(app)

    /** Bundled models and tables, loading in the background. */
    val models: Deferred<Models> = scope.async { Models(BundledAssets(app.assets)) }

    /** The single camera analysis thread; the encoders only ever run on it. */
    val analysis: ExecutorService = Executors.newSingleThreadExecutor()
}
