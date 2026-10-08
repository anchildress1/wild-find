package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * `labels.npy` rows with their `labels.json` entries: one row per menu word plus the fixed tutorial labels (R3).
 *
 * @property entries one entry per row of [vectors]
 * @property vectors unit BioCLIP teacher text vectors
 */
class LabelSet(val entries: List<Entry>, val vectors: FloatMatrix) {
    init {
        require(entries.size == vectors.rows) { "${entries.size} entries for ${vectors.rows} rows" }
        require(entries.distinctBy { it.id to it.kind }.size == entries.size) { "duplicate label ids" }
        val tutorial = entries.count { it.kind == Kind.TUTORIAL }
        require(tutorial == TUTORIAL_SIZE) { "$tutorial tutorial labels, expected $TUTORIAL_SIZE" }
        require(Entry(GRASS, Kind.TUTORIAL, GRASS) in entries) { "tutorial labels lack $GRASS" }
    }

    /**
     * One label row.
     *
     * @property id the menu word, or the scientific name for a tutorial label
     * @property kind menu word or tutorial label
     * @property scientific the scientific name the text vector embeds
     */
    data class Entry(val id: String, val kind: Kind, val scientific: String)

    /**
     * Which scoring set a row belongs to.
     *
     * @property json the `kind` value in `labels.json`
     */
    enum class Kind(val json: String) {
        /** A menu word, scored as a hunt target or candidate. */
        WORD("word"),

        /** One of the fixed grass-tutorial labels. */
        TUTORIAL("tutorial"),
        ;

        /** Parses `labels.json` kinds. */
        companion object {
            /** The kind named [json]; throws [IllegalArgumentException] for any other value. */
            fun of(json: String): Kind =
                requireNotNull(entries.firstOrNull { it.json == json }) { "unknown label kind $json" }
        }
    }

    /** The grass tutorial goal over the fixed tutorial labels. */
    fun tutorialGoal(): TutorialGoal = TutorialGoal(
        vectors,
        rowOf(GRASS, Kind.TUTORIAL),
        entries.indices.filter { entries[it].kind == Kind.TUTORIAL }.toIntArray(),
    )

    private fun rowOf(id: String, kind: Kind): Int =
        entries.indexOfFirst { it.id == id && it.kind == kind }.also { require(it >= 0) { "no $kind label $id" } }

    /** The fixed tutorial set from PRD R3. */
    companion object {
        /** The tutorial target's scientific name. */
        const val GRASS = "Poaceae"

        /** Poaceae, Quercus, Polypodiopsida, Trifolium, Pinus, Taraxacum, and the 5 hazard species. */
        const val TUTORIAL_SIZE = 11
    }
}
