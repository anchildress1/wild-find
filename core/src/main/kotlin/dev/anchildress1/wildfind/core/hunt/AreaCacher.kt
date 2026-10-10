package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.region.RegionKey

/**
 * Cache my area: loads the local list for every month of the year, so each pull lands in the cache and hunts there
 * work offline all year. Stops at the first month iNat can't answer and the cache can't either, since a rate limit
 * or a lost signal would fail the rest the same way.
 *
 * @property source the same source a hunt loads through, so every pull is cached under the key a hunt will ask for
 */
class AreaCacher(val source: LocalListSource) {
    /**
     * Pulls months 1 to [MONTHS] for [region] in [locale], calling [pause] between pulls to keep to iNat's request
     * rate and [progress] with the count after each month. Returns how many months are available offline.
     * Inline, so a coroutine caller can `delay` in [pause] and be cancelled between months.
     */
    inline fun cache(
        tableVersion: String,
        region: RegionKey,
        locale: String,
        pause: () -> Unit,
        progress: (Int) -> Unit,
    ): Int {
        var done = 0
        for (month in 1..MONTHS) {
            if (source.load(tableVersion, region, locale, month) == LocalListResult.NeedsSignal) return done
            progress(++done)
            if (month < MONTHS) pause()
        }
        return done
    }

    /** The span cached. */
    companion object {
        /** Calendar months. */
        const val MONTHS = 12
    }
}
