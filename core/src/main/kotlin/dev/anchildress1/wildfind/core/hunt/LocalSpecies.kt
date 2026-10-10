package dev.anchildress1.wildfind.core.hunt

import kotlin.math.max

/**
 * One species from an iNaturalist `species_counts` pull.
 *
 * @property scientific the scientific name iNat reports
 * @property common the common name in the requested locale, or null when iNat has none there
 * @property count research-grade sightings in the region for the month, across all years
 */
data class Sighting(val scientific: String, val common: String?, val count: Int)

/**
 * One species-table row as the local list sees it.
 *
 * @property scientific the table's scientific name
 * @property genus the row's genus
 * @property hazard a PRD hazard species
 * @property toxic flagged by the build-time toxicity rule
 * @property type the plant type shown with a target, or null when the build found none
 * @property synonyms other names GBIF gives this species, so a drifted iNat name still finds the row
 * @property description a generic kid-level sentence, the plant type in kid words, or null when the build found none
 * @property hints the build's ranked "where to look" hints, every seasonal one included; empty when it wrote none
 * @property hazardLine a short kid line for the warning card, on hazard rows only; null when the build wrote none
 * @property hazardFloor a fixed-floor hazard, which warns in every region; always a hazard
 * @property target false for a row the build holds back from target picks; it stays eligible, so it still competes
 *   in verify row 4
 */
data class SpeciesRow(
    val scientific: String,
    val genus: String,
    val hazard: Boolean,
    val toxic: Boolean,
    val type: PlantType? = null,
    val synonyms: List<String> = emptyList(),
    val description: String? = null,
    val hints: List<Hint> = emptyList(),
    val hazardLine: String? = null,
    val hazardFloor: Boolean = false,
    val target: Boolean = true,
) {
    /** Neither toxic nor a hazard, so it can be a target. */
    val playable: Boolean get() = !toxic && !hazard

    /** Validates `species_labels.json`. */
    companion object {
        /**
         * Returns [rows] after checking them against `species_table.npy`'s [tableRows]; throws
         * [IllegalArgumentException] on any break, so a mismatched build never ships.
         */
        fun checked(rows: List<SpeciesRow>, tableRows: Int): List<SpeciesRow> {
            require(rows.size == tableRows) { "${rows.size} species labels for $tableRows table rows" }
            require(rows.distinctBy { it.scientific }.size == rows.size) { "duplicate species names" }
            // Genus pass and the one-per-genus pick read this field, so it must be the name's own genus.
            rows.firstOrNull { it.genus != it.scientific.substringBefore(' ') }?.let {
                throw IllegalArgumentException("${it.scientific} has genus ${it.genus}")
            }
            require(rows.any { it.hazardFloor }) { "no floor hazard species" }
            val notHazard = rows.firstOrNull { it.hazardFloor && !it.hazard }
            require(notHazard == null) { "floor hazard ${notHazard?.scientific} is not a hazard" }
            val badLine = rows.firstOrNull { it.hazardLine != null && (!it.hazard || it.hazardLine.isBlank()) }
            require(badLine == null) { "${badLine?.scientific} has a bad hazard line" }
            return rows
        }
    }
}

/**
 * A playable species: a table row with the sightings and common name the hunt shows.
 *
 * @property row the species-table row
 * @property common the common name the kid sees
 * @property count sightings, summed over every iNat name that matched this row
 */
data class Eligible(val row: Int, val common: String, val count: Int)

/**
 * The hunt's local list from one pull (PRD R2).
 *
 * @property eligible playable species, most sighted first
 * @property blockers every local toxic-flagged and hazard row, at any sighting count; never targets, but they block
 *   verify row 4
 * @property needsWiden fewer than [LocalSpecies.MIN_TARGETS] pickable eligible genera, so the pull widens to 150 km
 *   once
 */
data class LocalList(val eligible: List<Eligible>, val blockers: IntArray, val needsWiden: Boolean) {
    override fun equals(other: Any?): Boolean = other is LocalList &&
        eligible == other.eligible &&
        blockers.contentEquals(other.blockers) &&
        needsWiden == other.needsWiden

    override fun hashCode(): Int = 31 * (31 * eligible.hashCode() + blockers.contentHashCode()) + needsWiden.hashCode()
}

/**
 * Applies PRD R2's eligibility rule to a pull.
 *
 * @param table every species-table row, in row order
 * @param rowOf the table row an iNat scientific name resolves to, through accepted names and synonyms, or null
 */
class LocalSpecies(private val table: List<SpeciesRow>, private val rowOf: (String) -> Int?) {
    /** The local list for [sightings], one pull's species at whichever radius it queried. */
    fun of(sightings: List<Sighting>): LocalList {
        // The share counts every plant sighting in the pull, matched to the table or not.
        val floor = max(MIN_SIGHTINGS.toDouble(), SHARE * sightings.sumOf { it.count })
        val byRow = sightings.mapNotNull { s ->
            rowOf(s.scientific)?.let { it to s }
        }.groupBy({ it.first }, { it.second })
        val counted = byRow.mapValues { (_, names) -> names.sumOf { it.count } }.filterValues { it >= floor }
        val eligible = counted.mapNotNull { (row, count) ->
            val common = byRow.getValue(row).sortedByDescending { it.count }
                .firstNotNullOfOrNull { it.common?.trim()?.takeIf(::isKidName) }
            if (table[row].playable && common != null) Eligible(row, common, count) else null
        }.sortedWith(compareByDescending<Eligible> { it.count }.thenBy { it.row })
        // Any sighting blocks: the floor-only blockers let 87 of 180 toxic photos pass as some target on Oct 7.
        val blockers = byRow.keys.filterNot { table[it].playable }.sorted().toIntArray()
        // A hunt takes one target per genus, so three species of two genera still can't fill it; a row held back from
        // picks can't fill a slot either.
        val genera = eligible.filter { table[it.row].target }.distinctBy { table[it.row].genus }.size
        return LocalList(eligible, blockers, needsWiden = genera < MIN_TARGETS)
    }

    private fun isKidName(name: String) = name.isNotEmpty() && name.split(WHITESPACE).size <= MAX_NAME_WORDS

    /** R2 constants, decided Oct 7 (`docs/results/day-2/playable_species.log`). */
    companion object {
        /** A species needs at least this share of the pull's plant sightings. */
        const val SHARE = 0.005

        /** And at least this many sightings, so one stray sighting never makes a target in a sparse place. */
        const val MIN_SIGHTINGS = 3

        /** Longest common name a kid is asked to find. */
        const val MAX_NAME_WORDS = 3

        /** A hunt picks this many targets, one per genus; fewer eligible genera widens the pull. */
        const val MIN_TARGETS = 3

        private val WHITESPACE = Regex("\\s+")
    }
}
