package dev.anchildress1.wildfind.core.hunt

/** A northern-hemisphere season, the unit USDA gives bloom and fruit periods in. */
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

        /** The season of calendar [month], 1 to 12; December through February is winter. */
        fun of(month: Int): Season {
            require(month in 1..MONTHS) { "month $month" }
            // Winter wraps the year, so the quarters are counted from December.
            return entries[((month % MONTHS) / MONTHS_PER_SEASON + entries.size - 1) % entries.size]
        }

        /** The season named [name] in the build's hint file, or null for no season. */
        fun named(name: String?): Season? = name?.let { valueOf(it.uppercase()) }
    }
}

/**
 * One "where to look" hint from the build.
 *
 * @property text the kid-facing sentence
 * @property season the season the hint holds in, or null when it holds all year
 */
data class Hint(val text: String, val season: Season? = null)

/**
 * Up to [limit] hints for [month], in-season ones first and the build's ranking kept within each group, so a
 * "blue flowers in summer" hint yields to a fall one in October.
 */
fun List<Hint>.forMonth(month: Int, limit: Int = 3): List<Hint> {
    val now = Season.of(month)
    val (inSeason, rest) = partition { it.season == now }
    val (yearRound, offSeason) = rest.partition { it.season == null }
    return (inSeason + yearRound + offSeason).take(limit)
}
