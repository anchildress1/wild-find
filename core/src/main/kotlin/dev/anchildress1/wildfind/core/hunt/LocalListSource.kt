package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.region.RegionKey

/** Where a hunt's local list came from, or why there is none (PRD Failure Handling). */
sealed interface LocalListResult {
    /**
     * Enough species to play.
     *
     * @property local the eligible species and blockers
     */
    data class Ready(val local: LocalList) : LocalListResult

    /** Still fewer than 3 eligible genera after the widen: "Not enough plants spotted here yet". */
    data object NotEnough : LocalListResult

    /** No answer from iNat and no matching cache: this place needs signal once. */
    data object NeedsSignal : LocalListResult
}

/**
 * Loads a hunt's local list: one iNat query, falling back to a matching cache entry when iNat doesn't answer, and
 * one widened query when fewer than 3 genera are eligible.
 *
 * @param species the eligibility rule
 * @param pull runs one query; null when iNat failed or rate-limited
 * @param cached the sightings cached under exactly this key, or null
 * @param save caches a fresh pull under its key; true only after a successful durable write
 */
class LocalListSource(
    private val species: LocalSpecies,
    private val pull: (SpeciesCountsQuery) -> List<Sighting>?,
    private val cached: (CacheKey) -> List<Sighting>?,
    private val save: (CacheKey, List<Sighting>) -> Boolean,
) {
    /** Loads [region]'s list for [month]; [requireCached] accepts only sightings available offline. */
    fun load(
        tableVersion: String,
        region: RegionKey,
        locale: String,
        month: Int,
        requireCached: Boolean = false,
    ): LocalListResult {
        fun at(radiusKm: Int): LocalList? {
            val key = CacheKey(tableVersion, region, locale, month, radiusKm)
            val fresh = pull(SpeciesCountsQuery(region, month, locale, radiusKm))
            val saved = fresh?.let { save(key, it) } == true
            val sightings = if (fresh != null && (!requireCached || saved)) fresh else cached(key)
            return sightings?.let(species::of)
        }
        val near = at(CacheKey.RADIUS_KM)
        val wide = if (near?.needsWiden == true) at(CacheKey.WIDE_RADIUS_KM) else null
        return when {
            near == null -> LocalListResult.NeedsSignal
            !near.needsWiden -> LocalListResult.Ready(near)
            wide == null -> LocalListResult.NeedsSignal
            wide.needsWiden -> LocalListResult.NotEnough
            else -> LocalListResult.Ready(wide)
        }
    }
}
