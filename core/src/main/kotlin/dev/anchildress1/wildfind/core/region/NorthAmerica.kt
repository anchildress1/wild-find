package dev.anchildress1.wildfind.core.region

/**
 * Where the fixed hazard floor warns unseen: a whole-degree box from Alaska through Panama and the Caribbean, which
 * leaves out eastern Greenland and all of Europe.
 *
 * The floor's species (every Toxicodendron, pokeweed, Carolina horsenettle) are North American, and here they must
 * warn even when the pull misses them: iNat's one-month West Georgia pull names neither Atlantic poison oak nor poison
 * sumac. Elsewhere they warn only where the pull names them, like every other hazard (decided Oct 10).
 */
object NorthAmerica {
    /** Southern edge, inclusive: Panama's south coast. */
    const val MIN_LAT = 5

    /** Northern edge, inclusive: the Arctic coast of Alaska and Canada. */
    const val MAX_LAT = 72

    /** Western edge, inclusive: mainland Alaska. */
    const val MIN_LNG = -170

    /** Eastern edge, inclusive: Newfoundland and western Greenland. */
    const val MAX_LNG = -50

    /** True when [region]'s center, in whole degrees, lies inside the box. */
    fun contains(region: RegionKey): Boolean = region.lat in MIN_LAT..MAX_LAT && region.lng in MIN_LNG..MAX_LNG
}
