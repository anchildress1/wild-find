package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * PRD verify row 1: ranks a region's BioCLIP embedding against the whole species table.
 *
 * Only [local] rows and hazard rows compete, so a plant from another continent can't outrank what grows here.
 *
 * @param table one unit text vector per species
 * @param hazard true for each PRD hazard species row
 * @param local true for each species seen near the player; hazards always compete
 */
class HazardCheck(private val table: FloatMatrix, private val hazard: BooleanArray, local: BooleanArray) {
    init {
        require(hazard.size == table.rows) { "${hazard.size} hazard flags for ${table.rows} rows" }
        require(local.size == table.rows) { "${local.size} local flags for ${table.rows} rows" }
        require(hazard.any { it }) { "no hazard species in the table" }
    }

    // After the size checks, so a short flag array fails them instead of indexing past its end.
    private val ranked = IntArray(table.rows) { it }.filter { hazard[it] || local[it] }.toIntArray()

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

    /** Ranks [embedding] against the local and hazard rows; row numbers stay those of the whole table. */
    fun rank(embedding: FloatArray): Ranking {
        val scores = DoubleArray(table.rows)
        for (row in ranked) scores[row] = table.dot(row, embedding)
        var topRow = ranked.first()
        var hazardRow = -1
        for (row in ranked) {
            if (scores[row] > scores[topRow]) topRow = row
            if (hazard[row] && (hazardRow < 0 || scores[row] > scores[hazardRow])) hazardRow = row
        }
        val best = scores[hazardRow]
        return Ranking(topRow, hazardRow, 1 + ranked.count { scores[it] > best })
    }

    /** Hazard rule constants measured on Day 1. */
    companion object {
        /** A hazard warns within this many top species; caught 48 of 52 hazard photos, warned on 1 of 253 safe ones. */
        const val TOP_K = 5
    }
}
