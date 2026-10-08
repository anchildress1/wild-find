package dev.anchildress1.wildfind.core.cache

import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.region.RegionKey
import java.security.MessageDigest

/**
 * Everything a cached pull depends on; an entry serves a hunt only when its key equals the wanted key.
 *
 * @property tableVersion [tableVersion] of the shipped `species_labels.json`, so a rebuilt table or flag set misses
 * @property region the whole-degree region the pull covered
 * @property locale the device language the common names came in
 * @property month the calendar month, 1 to 12
 * @property radiusKm [RADIUS_KM], or [WIDE_RADIUS_KM] after the widen, so widened counts never pass as near ones
 * @property schemaVersion the cache entry format
 */
data class CacheKey(
    val tableVersion: String,
    val region: RegionKey,
    val locale: String,
    val month: Int,
    val radiusKm: Int,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(month in 1..MONTHS) { "month $month" }
        require(radiusKm == RADIUS_KM || radiusKm == WIDE_RADIUS_KM) { "radius $radiusKm km" }
        require(locale.isNotBlank()) { "blank locale" }
    }

    /** Cache constants from the PRD Data Contracts. */
    companion object {
        /** The only cache entry format this build reads. */
        const val SCHEMA_VERSION = 1

        /** The first pull's radius. */
        const val RADIUS_KM = 75

        /** The one widened pull's radius, when the first leaves too few species. */
        const val WIDE_RADIUS_KM = 150

        private const val MONTHS = 12
        private const val VERSION_HEX = 12

        /** The first 12 hex digits of the SHA-256 of `species_labels.json`'s bytes. */
        fun tableVersion(speciesLabels: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(speciesLabels)
            .joinToString("") { "%02x".format(it) }
            .take(VERSION_HEX)
    }
}

/**
 * One cached iNat pull: every species it returned, so the local list and its blockers rebuild offline.
 *
 * @property key what the pull depended on
 * @property sightings every species the pull returned, matched to the table or not, since the share floor counts all
 */
data class CacheEntry(val key: CacheKey, val sightings: List<Sighting>) {
    /** This entry's sightings when it was pulled under [wanted], else null: any mismatch discards it. */
    fun sightingsFor(wanted: CacheKey): List<Sighting>? = sightings.takeIf { key == wanted }
}
