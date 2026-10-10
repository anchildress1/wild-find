package dev.anchildress1.wildfind.core.hunt

/** A season as the northern hemisphere has it, the unit USDA gives bloom and fruit periods in. */
enum class Season {
    /** March to May. */
    SPRING,

    /** June to August. */
    SUMMER,

    /** September to November. */
    FALL,

    /** December to February. */
    WINTER,
    ;

    /** Season lookup and the app's name for it in `species_labels.json`. */
    companion object {
        private const val MONTHS = 12
        private const val MONTHS_PER_SEASON = 3

        /**
         * The season of calendar [month], 1 to 12; December through February is winter in the north. A southern
         * region has the opposite season, so its spring is the north's fall.
         */
        fun of(month: Int, northern: Boolean = true): Season {
            require(month in 1..MONTHS) { "month $month" }
            // Winter wraps the year, so the quarters are counted from December.
            val north = entries[((month % MONTHS) / MONTHS_PER_SEASON + entries.size - 1) % entries.size]
            return if (northern) north else entries[(north.ordinal + entries.size / 2) % entries.size]
        }

        /** The season named [name] in the hint file; null for no season or a name this build does not know. */
        fun named(name: String?): Season? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * One "where to look" hint from the build.
 *
 * @property text the kid-facing sentence
 * @property season the season the hint holds in, or null when it holds all year
 */
data class Hint(val text: String, val season: Season? = null)

/** The most hints a target shows. */
const val HINTS_PER_TARGET = 3

/**
 * Up to [limit] hints for [month], in-season ones first and the build's ranking kept within each group, so a
 * "blue flowers in summer" hint yields to a fall one in October. [northern] is false for a region south of the
 * equator, where the seasons run opposite.
 */
fun List<Hint>.forMonth(month: Int, limit: Int = HINTS_PER_TARGET, northern: Boolean = true): List<Hint> {
    val now = Season.of(month, northern)
    val (inSeason, rest) = partition { it.season == now }
    val (yearRound, offSeason) = rest.partition { it.season == null }
    return (inSeason + yearRound + offSeason).take(limit)
}
