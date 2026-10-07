package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * PRD verify row 1: ranks a region's BioCLIP embedding against the whole species table.
 *
 * @param table one unit text vector per species
 * @param hazard true for each PRD hazard species row
 */
class HazardCheck(private val table: FloatMatrix, private val hazard: BooleanArray) {
    init {
        require(hazard.size == table.rows) { "${hazard.size} hazard flags for ${table.rows} rows" }
        require(hazard.any { it }) { "no hazard species in the table" }
    }

    /**
     * One region's embedding ranked against every row.
     *
     * @property topRow the best-scoring species row
     * @property hazardRow the best-scoring hazard species row
     * @property hazardRank 1-based rank of [hazardRow]; a safe species tied with it ranks below it, so ties warn
     */
    data class Ranking(val topRow: Int, val hazardRow: Int, val hazardRank: Int) {
        /** True when the best hazard is within the top [TOP_K]. */
        val warns: Boolean get() = hazardRank <= TOP_K
    }

    /** Ranks [embedding] against the whole table. */
    fun rank(embedding: FloatArray): Ranking {
        val scores = DoubleArray(table.rows) { table.dot(it, embedding) }
        var topRow = 0
        var hazardRow = -1
        for (row in scores.indices) {
            if (scores[row] > scores[topRow]) topRow = row
            if (hazard[row] && (hazardRow < 0 || scores[row] > scores[hazardRow])) hazardRow = row
        }
        val best = scores[hazardRow]
        return Ranking(topRow, hazardRow, 1 + scores.count { it > best })
    }

    /** Hazard rule constants measured on Day 1. */
    companion object {
        /** A hazard warns within this many top species; caught 48 of 52 hazard photos, warned on 1 of 253 safe ones. */
        const val TOP_K = 5
    }
}
