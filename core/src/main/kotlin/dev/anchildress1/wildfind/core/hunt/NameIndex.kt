package dev.anchildress1.wildfind.core.hunt

/**
 * Resolves an iNat scientific name to its species-table row; the one place names are matched (PRD hole 22).
 *
 * @param rows every species-table row, in row order
 */
class NameIndex(rows: List<SpeciesRow>) {
    private val byName = rows.withIndex().flatMap { (row, species) ->
        (species.synonyms + species.scientific).map { it to row }
    }.toMap().also { names ->
        // An alias naming two rows would merge species; the build drops those, so this only guards a bad build.
        require(names.size == rows.sumOf { it.synonyms.size + 1 }) { "a name maps to two species rows" }
    }

    /** The row [scientific] names, as the table's own name or one of its synonyms, or null. */
    fun rowOf(scientific: String): Int? = byName[scientific]
}
