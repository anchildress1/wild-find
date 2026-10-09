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
 * Picks a hunt's targets by sighting-weighted random from the local list, species with hints first: a kid
 * finds a plant faster when told where to look. Eligibility never depends on it, since hints exist only for
 * rows with a sourced article or USDA trait and a hard filter would empty hunts elsewhere.
 *
 * @param table every species-table row, for each target's genus and hints
 * @param random the draw; seeded in tests
 */
class HuntPick(private val table: List<SpeciesRow>, private val random: Random = Random.Default) {
    /**
     * A hunt from [local]; [tutorialDone] false opens it with the grass tutorial. Targets come from hinted species
     * until those run out, then from the rest; the skip queue likewise lists hinted species first, each group
     * shuffled.
     */
    fun next(local: LocalList, tutorialDone: Boolean): Hunt {
        val (hinted, plain) = local.eligible.partition(::hinted)
        val targets = mutableListOf<Eligible>()
        fill(targets, hinted)
        fill(targets, plain)
        val (later, last) = (local.eligible - targets.toSet()).partition(::hinted)
        return Hunt(!tutorialDone, targets, later.shuffled(random) + last.shuffled(random))
    }

    private fun hinted(species: Eligible) = table[species.row].hints.isNotEmpty()

    // Adds weighted draws from [from] until the hunt is full, never repeating a genus already in it.
    private fun fill(targets: MutableList<Eligible>, from: List<Eligible>) {
        val taken = targets.map { table[it.row].genus }.toSet()
        val left = from.filterNot { table[it.row].genus in taken }.toMutableList()
        while (targets.size < TARGETS && left.isNotEmpty()) {
            val chosen = draw(left)
            targets += chosen
            left.removeAll { table[it.row].genus == table[chosen.row].genus }
        }
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
