package dev.anchildress1.wildfind.core.hunt

/**
 * Resolves an iNat scientific name to its species-table row; the one place names are matched (PRD hole 22).
 *
 * @param rows every species-table row, in row order
 */
class NameIndex(rows: List<SpeciesRow>) {
    private val byName = rows.withIndex().associate { (row, species) -> species.scientific to row }

    /** The row [scientific] names exactly, or null. */
    fun rowOf(scientific: String): Int? = byName[scientific]
}
