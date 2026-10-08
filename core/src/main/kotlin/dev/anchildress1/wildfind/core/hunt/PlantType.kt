package dev.anchildress1.wildfind.core.hunt

/**
 * A species' plant type, shown with the target's name from the start of a hunt (PRD Decisions: Plant type).
 *
 * @property key the `type` value in `species_labels.json`
 */
enum class PlantType(val key: String) {
    /** USDA growth habit tree. */
    TREE("tree"),

    /** USDA growth habit shrub. */
    SHRUB("shrub"),

    /** USDA growth habit vine. */
    VINE("vine"),

    /** USDA growth habit forb/herb. */
    HERB("herb"),

    /** USDA growth habit graminoid, or the grass family. */
    GRASS("grass"),

    /** Ferns, from taxonomy. */
    FERN("fern"),

    /** Mosses, from taxonomy. */
    MOSS("moss"),

    /** Conifers, from taxonomy. */
    CONIFER("conifer"),
    ;

    /** Reads `species_labels.json`'s `type`. */
    companion object {
        /** The type named [key], or null for none; an unknown name throws, so a pipeline change can't ship unseen. */
        fun of(key: String?): PlantType? = key?.let { name ->
            requireNotNull(entries.firstOrNull { it.key == name }) { "unknown plant type $name" }
        }
    }
}
