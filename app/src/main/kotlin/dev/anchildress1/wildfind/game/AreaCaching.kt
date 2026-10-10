package dev.anchildress1.wildfind.game

import android.util.Log
import dev.anchildress1.wildfind.Graph
import dev.anchildress1.wildfind.Models
import dev.anchildress1.wildfind.core.game.Command
import dev.anchildress1.wildfind.core.hunt.AreaCacher
import dev.anchildress1.wildfind.core.hunt.LocalListSource
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.inat.InatLocale
import dev.anchildress1.wildfind.core.inat.RetryWindow
import dev.anchildress1.wildfind.core.region.RegionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// iNat asks clients to stay under about a request a second; each month is one or two requests.
private const val MONTH_PAUSE_MS = 1_500L
private const val TAG = "AreaCaching"

/** The one Cache my area run: a new run or an area change stops the old one, and a new run waits for it to end. */
internal class AreaRun {
    private var job: Job? = null

    /** Starts [command]'s run, or only stops the current one for [Command.CancelCacheArea]. */
    fun on(command: Command, scope: CoroutineScope, work: suspend (RegionKey) -> Unit) {
        val old = job?.also { it.cancel() }
        // The old run may be mid-pull, which cannot be interrupted; waiting keeps two runs from overlapping on iNat.
        job = (command as? Command.CacheArea)?.let {
            scope.launch {
                old?.join()
                work(it.region)
            }
        }
    }
}

/**
 * Runs [cache], which saves the months and calls its argument with the count after each; [report] gets that count
 * after each month, then once more with `finished = true`. An unexpected failure ends the run as stopped at the months
 * saved so far, so the card never stays on Running.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runAreaCache(
    cache: suspend (onMonth: suspend (Int) -> Unit) -> Int,
    report: suspend (done: Int, finished: Boolean) -> Unit,
) {
    var saved = 0
    val done = try {
        cache {
            saved = it
            report(it, false)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cache my area stopped", e)
        saved
    }
    report(done, true)
}

/**
 * Saves every month of [region] into the hunt cache, off the main thread; [onMonth] gets the count after each month.
 * Returns the months saved, fewer than 12 when iNat stopped answering. Months answered from the cache or skipped by an
 * open [retry] window send no request, so only a month that did is followed by the pause.
 */
internal suspend fun cacheArea(
    graph: Graph,
    models: Models,
    region: RegionKey,
    retry: RetryWindow,
    onMonth: suspend (Int) -> Unit,
): Int = withContext(Dispatchers.IO) {
    var requests = 0
    var paused = 0
    AreaCacher(
        LocalListSource(
            LocalSpecies(models.rows, models.names::rowOf),
            pull = { query ->
                retry.pull {
                    requests++
                    graph.inat.pull(query)
                }
            },
            cached = graph.store::cached,
            save = graph.store::cache,
        ),
    ).cache(
        models.tableVersion,
        region,
        InatLocale.of(Locale.getDefault().toLanguageTag()),
        pause = {
            if (requests > paused) {
                paused = requests
                delay(MONTH_PAUSE_MS)
            }
        },
        progress = { onMonth(it) },
    )
}
