package dev.anchildress1.wildfind.core.hunt

import kotlin.random.Random

/**
 * One hunt's plan (PRD R3, R4).
 *
 * @property tutorial true when the grass tutorial opens the hunt
 * @property targets up to [HuntPick.TARGETS] species, never two from one genus; fewer means the coverage message
 * @property queue every other eligible species, shuffled; a skip swaps the next one in
 */
data class Hunt(val tutorial: Boolean, val targets: List<Eligible>, val queue: List<Eligible> = emptyList())

/**
 * Picks a hunt's targets by sighting-weighted random from the local list.
 *
 * @param table every species-table row, for each target's genus
 * @param random the draw; seeded in tests
 */
class HuntPick(private val table: List<SpeciesRow>, private val random: Random = Random.Default) {
    /** A hunt from [local]; [tutorialDone] false opens it with the grass tutorial. */
    fun next(local: LocalList, tutorialDone: Boolean): Hunt {
        val left = local.eligible.toMutableList()
        val targets = mutableListOf<Eligible>()
        while (targets.size < TARGETS && left.isNotEmpty()) {
            val chosen = draw(left)
            targets += chosen
            left.removeAll { table[it.row].genus == table[chosen.row].genus }
        }
        return Hunt(!tutorialDone, targets, (local.eligible - targets.toSet()).shuffled(random))
    }

    private fun draw(from: List<Eligible>): Eligible {
        var ticket = random.nextLong(from.sumOf { it.count.toLong() })
        return from.first {
            ticket -= it.count
            ticket < 0
        }
    }

    /** Hunt shape from PRD R4. */
    companion object {
        /** Targets per hunt. */
        const val TARGETS = 3
    }
}
