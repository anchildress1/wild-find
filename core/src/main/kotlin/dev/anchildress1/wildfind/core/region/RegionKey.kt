package dev.anchildress1.wildfind.core.region

import kotlin.math.roundToInt

/**
 * Whole-degree region, keyed as `"<lat>_<lng>"` (e.g. `34_-85`).
 *
 * @property lat latitude in whole degrees
 * @property lng longitude in whole degrees
 */
data class RegionKey(val lat: Int, val lng: Int) {
    /** True when wild-find ships a menu for this region. */
    val isSupported: Boolean get() = this in SUPPORTED

    override fun toString(): String = "${lat}_$lng"

    companion object {
        /** The only supported v1 key; the iNat query covers 75 km around its center, (34, -85). */
        val WEST_GEORGIA = RegionKey(34, -85)
        private val SUPPORTED = setOf(WEST_GEORGIA)

        /** Rounds coarse coordinates to whole degrees; ties round toward positive infinity. */
        fun from(latitude: Double, longitude: Double): RegionKey =
            RegionKey(latitude.roundToInt(), longitude.roundToInt())
    }
}
