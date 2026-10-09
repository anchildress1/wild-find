package dev.anchildress1.wildfind.core.inat

import dev.anchildress1.wildfind.core.hunt.Sighting

/** What one species_counts query returned. */
sealed interface Pull {
    /**
     * Every species the query returned.
     *
     * @property sightings one per taxon, any rank; only species ever match the table
     */
    data class Pulled(val sightings: List<Sighting>) : Pull

    /**
     * iNat answered 429.
     *
     * @property retryAfterSeconds the wait it asked for, or null when it gave none
     */
    data class RateLimited(val retryAfterSeconds: Long?) : Pull

    /** No usable answer: no signal, a server error, or a malformed body. */
    data object Failed : Pull
}
