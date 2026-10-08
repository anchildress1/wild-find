package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.region.RegionKey

/**
 * The fixed app flags that outlive a hunt (H2); nothing else persists beyond the current hunt.
 *
 * @property openerSeen the safety opener (R1) has played once; it stays replayable from the menu
 * @property tutorialDone the grass tutorial (R3) passed, so it never repeats
 * @property region the hunting area, from the rough location or the manual pick; changed from the grown-ups page
 */
data class AppFlags(val openerSeen: Boolean = false, val tutorialDone: Boolean = false, val region: RegionKey? = null)

/**
 * The current hunt, the only hunt state that persists; the app saves it on every change and restores it after
 * process death.
 *
 * @property tutorialPending the grass tutorial still opens the hunt
 * @property targets this hunt's targets, in pick order
 * @property found the rows of targets already found; the kid finds them in any order
 */
data class HuntProgress(val tutorialPending: Boolean, val targets: List<Eligible>, val found: Set<Int> = emptySet()) {
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

    /** Starts the hunt [hunt] planned. */
    companion object {
        /** A fresh hunt with nothing found. */
        fun start(hunt: Hunt): HuntProgress = HuntProgress(hunt.tutorial, hunt.targets)
    }
}
