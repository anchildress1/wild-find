package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.region.RegionKey

/**
 * The fixed app flags that outlive a hunt (H2); nothing else persists beyond the current hunt.
 *
 * @property openerSeen the safety opener (R1) has played once; it stays replayable from the menu
 * @property tutorialDone the grass tutorial (R3) passed, so it never repeats
 * @property region the hunting area, from the rough location or the manual pick; changed from the grown-ups page
 * @property locationDenied rough location was denied, so it is never asked for again
 */
data class AppFlags(
    val openerSeen: Boolean = false,
    val tutorialDone: Boolean = false,
    val region: RegionKey? = null,
    val locationDenied: Boolean = false,
)

/**
 * The current hunt, the only hunt state that persists; the app saves it on every change and restores it after
 * process death.
 *
 * @property tutorialPending the grass tutorial still opens the hunt
 * @property targets this hunt's targets, in pick order
 * @property found the rows of targets already found; the kid finds them in any order
 * @property queue the hunt's other eligible species, next in line for a skip
 */
data class HuntProgress(
    val tutorialPending: Boolean,
    val targets: List<Eligible>,
    val found: Set<Int> = emptySet(),
    val queue: List<Eligible> = emptyList(),
) {
    init {
        require(targets.distinctBy { it.row }.size == targets.size) { "repeated target" }
        require(found.all { row -> targets.any { it.row == row } }) { "found row outside the hunt" }
    }

    /** One star per find (R15). */
    val stars: Int get() = found.size

    /** True once the tutorial is done and every target is found. */
    val complete: Boolean get() = !tutorialPending && found.size == targets.size

    /** Targets still to find, in pick order. */
    val remaining: List<Eligible> get() = targets.filterNot { it.row in found }

    /** After the grass tutorial passes; the app also sets [AppFlags.tutorialDone]. */
    fun tutorialPassed(): HuntProgress {
        check(tutorialPending) { "no tutorial in this hunt" }
        return copy(tutorialPending = false)
    }

    /** After verify reports Found for the target at [row]; targets wait until the tutorial passes. */
    fun targetFound(row: Int): HuntProgress {
        check(!tutorialPending) { "the tutorial comes first" }
        require(targets.any { it.row == row }) { "row $row is not a target" }
        return copy(found = found + row)
    }

    /**
     * The kid skipped the target at [row]: the first species in the queue whose genus isn't already on screen takes
     * its place, and the skipped one goes to the back of the queue. With no such species nothing changes.
     *
     * @param genusOf each species row's genus; verify passes on genus, so two targets in one genus could share a find
     */
    fun skip(row: Int, genusOf: (Int) -> String): HuntProgress {
        check(!tutorialPending) { "the tutorial comes first" }
        require(targets.any { it.row == row } && row !in found) { "row $row is not an open target" }
        val onScreen = targets.filter { it.row != row }.map { genusOf(it.row) }.toSet()
        val next = queue.firstOrNull { genusOf(it.row) !in onScreen } ?: return this
        val skipped = targets.first { it.row == row }
        return copy(targets = targets.map { if (it.row == row) next else it }, queue = queue - next + skipped)
    }

    /** Starts the hunt [hunt] planned. */
    companion object {
        /** A fresh hunt with nothing found. */
        fun start(hunt: Hunt): HuntProgress = HuntProgress(hunt.tutorial, hunt.targets, queue = hunt.queue)
    }
}
