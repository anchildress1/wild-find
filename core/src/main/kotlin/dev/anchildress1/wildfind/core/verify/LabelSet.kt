package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * `labels.npy` rows with their `labels.json` scientific names: the fixed grass-tutorial labels (R3).
 *
 * @property names one scientific name per row of [vectors]
 * @property vectors unit BioCLIP teacher text vectors
 */
class LabelSet(val names: List<String>, val vectors: FloatMatrix) {
    init {
        require(names.size == vectors.rows) { "${names.size} names for ${vectors.rows} rows" }
        require(names.distinct().size == names.size) { "duplicate labels" }
        require(names.size == TUTORIAL_SIZE) { "${names.size} tutorial labels, expected $TUTORIAL_SIZE" }
        require(GRASS in names) { "tutorial labels lack $GRASS" }
    }

    /** The grass tutorial goal over every tutorial label. */
    fun tutorialGoal(): TutorialGoal = TutorialGoal(vectors, names.indexOf(GRASS), names.indices.toList().toIntArray())

    /** The fixed tutorial set from PRD R3. */
    companion object {
        /** The tutorial target's scientific name. */
        const val GRASS = "Poaceae"

        /** Poaceae, Quercus, Polypodiopsida, Trifolium, Pinus, Taraxacum, and the 5 hazard species. */
        const val TUTORIAL_SIZE = 11
    }
}
