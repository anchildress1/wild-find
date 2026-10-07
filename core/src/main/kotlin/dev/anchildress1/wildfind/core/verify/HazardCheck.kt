package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import dev.anchildress1.wildfind.core.tensor.dotAt

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
     * 1-based rank of the best-scoring hazard species among all rows.
     *
     * A safe species tied with the hazard ranks below it, so ties warn rather than pass.
     */
    fun bestHazardRank(embedding: FloatArray): Int {
        require(embedding.size == table.cols) { "embedding size ${embedding.size}, expected ${table.cols}" }
        val scores = DoubleArray(table.rows) { table.data.dotAt(it * table.cols, embedding) }
        var best = Double.NEGATIVE_INFINITY
        for (row in scores.indices) if (hazard[row] && scores[row] > best) best = scores[row]
        return 1 + scores.count { it > best }
    }

    /** True when a hazard species ranks in the top [TOP_K]. */
    fun warns(embedding: FloatArray): Boolean = bestHazardRank(embedding) <= TOP_K

    /** Hazard rule constants measured on Day 1. */
    companion object {
        /** A hazard warns within this many top species; caught 48 of 52 hazard photos, warned on 1 of 253 safe ones. */
        const val TOP_K = 5
    }
}
