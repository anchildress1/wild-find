package dev.anchildress1.wildfind.game

import dev.anchildress1.wildfind.Graph
import dev.anchildress1.wildfind.Models
import dev.anchildress1.wildfind.core.hunt.AreaCacher
import dev.anchildress1.wildfind.core.hunt.LocalListSource
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.inat.InatLocale
import dev.anchildress1.wildfind.core.inat.RetryWindow
import dev.anchildress1.wildfind.core.region.RegionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// iNat asks clients to stay under about a request a second; each month is one or two requests.
private const val MONTH_PAUSE_MS = 1_500L

/**
 * Saves every month of [region] into the hunt cache, off the main thread; [onMonth] gets the count after each month.
 * Returns the months saved, fewer than 12 when iNat stopped answering. A [retry] window already open stops it at once.
 */
internal suspend fun cacheArea(
    graph: Graph,
    models: Models,
    region: RegionKey,
    retry: RetryWindow,
    onMonth: (Int) -> Unit,
): Int = withContext(Dispatchers.IO) {
    AreaCacher(
        LocalListSource(
            LocalSpecies(models.rows, models.names::rowOf),
            pull = { query -> retry.pull { graph.inat.pull(query) } },
            cached = graph.store::cached,
            save = graph.store::cache,
        ),
    ).cache(
        models.tableVersion,
        region,
        InatLocale.of(Locale.getDefault().toLanguageTag()),
        pause = { Thread.sleep(MONTH_PAUSE_MS) },
        progress = onMonth,
    )
}
