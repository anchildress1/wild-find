package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.region.NorthAmerica
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * PRD verify row 1: ranks a region's BioCLIP embedding against the whole species table.
 *
 * The hazard rank always counts every row, because [TOP_K] was measured that way; a smaller pool would push hazards
 * up and warn on ordinary plants. Only the reported top species is limited to [local] rows, so a plant from another
 * continent isn't named as what the kid sees.
 *
 * A hazard warns when it is seen near the player, or when it is on the fixed floor and [region] is inside
 * [NorthAmerica] (rule C, the floor unseen only there); any other hazard ranks as an ordinary species. Listing every
 * contact hazard warned on 18 of 253 safe Day-1 photos, rule B on 3 (`docs/results/day-4/hazard_gate/gate.log`).
 * Where no hazard can warn, nothing does.
 *
 * @param table one unit text vector per species
 * @param hazard true for each hazard species row, floor and contact list
 * @param floor true for each fixed-floor hazard row
 * @param local true for each species seen near the player
 * @param region the hunt's region; inside [NorthAmerica] a floor hazard warns even when the pull never named it
 */
class HazardCheck(
    private val table: FloatMatrix,
    hazard: BooleanArray,
    floor: BooleanArray,
    local: BooleanArray,
    region: RegionKey,
) {
    init {
        require(hazard.size == table.rows) { "${hazard.size} hazard flags for ${table.rows} rows" }
        require(floor.size == table.rows) { "${floor.size} floor flags for ${table.rows} rows" }
        require(local.size == table.rows) { "${local.size} local flags for ${table.rows} rows" }
    }

    // After the size checks, so a short flag array fails them instead of indexing past its end. With no local row,
    // the top species falls back to the whole table rather than naming nothing.
    private val warningRows: IntArray = NorthAmerica.contains(region).let { floorAlways ->
        (0 until table.rows).filter { (floor[it] && floorAlways) || (hazard[it] && local[it]) }.toIntArray()
    }
    private val named: List<Int> = (0 until table.rows).filter { local[it] }.ifEmpty { (0 until table.rows).toList() }

    /**
     * One region's embedding ranked against every row.
     *
     * @property topRow the best-scoring local species row
     * @property hazardRow the best-scoring hazard row that can warn here, or null when none can
     * @property hazardRank 1-based rank of [hazardRow], or null without one; a safe species tied with it ranks below
     *   it, so ties warn
     */
    data class Ranking(val topRow: Int, val hazardRow: Int?, val hazardRank: Int?) {
        /** True when the best hazard is within the top [TOP_K]. */
        val warns: Boolean get() = hazardRank != null && hazardRank <= TOP_K
    }

    /** Ranks [embedding]: the best warning hazard against every row, the top species among the local rows. */
    fun rank(embedding: FloatArray): Ranking {
        val scores = DoubleArray(table.rows) { table.dot(it, embedding) }
        val hazardRow = warningRows.maxByOrNull { scores[it] }
        val topRow = named.maxBy { scores[it] }
        val rank = hazardRow?.let { row -> 1 + scores.count { it > scores[row] } }
        return Ranking(topRow, hazardRow, rank)
    }

    /** Hazard rule constants measured on Day 1. */
    companion object {
        /** A hazard warns within this many top species; caught 48 of 52 hazard photos, warned on 1 of 253 safe ones. */
        const val TOP_K = 5
    }
}
