package dev.anchildress1.wildfind.core.region

import kotlin.math.roundToInt

/**
 * Whole-degree region, keyed as `"<lat>_<lng>"` (e.g. `34_-85`); any key plays, and the iNat query sends its center.
 *
 * @property lat latitude in whole degrees, the center the query sends
 * @property lng longitude in whole degrees, the center the query sends
 */
data class RegionKey(val lat: Int, val lng: Int) {
    override fun toString(): String = "${lat}_$lng"

    /** Rounds device coordinates into a region. */
    companion object {
        /** Rounds coarse coordinates to whole degrees; ties round toward positive infinity. */
        fun from(latitude: Double, longitude: Double): RegionKey =
            RegionKey(latitude.roundToInt(), longitude.roundToInt())
    }
}
