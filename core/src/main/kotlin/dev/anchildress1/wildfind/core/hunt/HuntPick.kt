package dev.anchildress1.wildfind.core.hunt

import kotlin.random.Random

/**
 * One hunt's plan (PRD R3, R4).
 *
 * @property tutorial true when the grass tutorial opens the hunt
 * @property targets up to [HuntPick.TARGETS] species, never two from one genus; fewer means the coverage message
 * @property queue every other pickable eligible species, shuffled; a skip swaps the next one in
 */
data class Hunt(val tutorial: Boolean, val targets: List<Eligible>, val queue: List<Eligible> = emptyList())

/**
 * Picks a hunt's targets by sighting-weighted random from the local list. Plants found in an earlier hunt come last,
 * so they appear only when too few others remain to fill the hunt; within each group species with hints come first,
 * since a kid finds a plant faster when told where to look. Eligibility never depends on either: hints exist only
 * for rows with a sourced article or USDA trait, and a hard filter would empty hunts in small areas. Rows the build
 * marks not [SpeciesRow.target] are never picked or queued: BioCLIP confirmed none of their fresh photos on Day 5.
 *
 * @param table every species-table row, for each target's genus, name, and hints
 * @param random the draw; seeded in tests
 */
class HuntPick(private val table: List<SpeciesRow>, private val random: Random = Random.Default) {
    /**
     * A hunt from [local]; [tutorialDone] false opens it with the grass tutorial. [found] holds the scientific names
     * of plants found before. Targets come from unfound hinted species until those run out, then unfound plain ones,
     * then found hinted, then found plain; the skip queue lists the rest in the same order, each group shuffled.
     */
    fun next(local: LocalList, tutorialDone: Boolean, found: Set<String> = emptySet()): Hunt {
        val pickable = local.eligible.filter { table[it.row].target }
        val (seen, fresh) = pickable.partition { table[it.row].scientific in found }
        val groups = fresh.partition(::hinted).toList() + seen.partition(::hinted).toList()
        val targets = mutableListOf<Eligible>()
        groups.forEach { fill(targets, it) }
        return Hunt(!tutorialDone, targets, groups.flatMap { (it - targets.toSet()).shuffled(random) })
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
