package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * How one frame scored against a goal.
 *
 * @property met true when the frame meets the goal on its own; verify still needs 3 such frames in a row
 * @property score the goal label's score: its cosine, or for a target with a full-frame plant, the mean of its
 *   reticle and full-frame cosines
 * @property rank the goal label's 1-based rank among the scored labels
 */
data class GoalScore(val met: Boolean, val score: Double, val rank: Int)

/** What the current hunt step looks for, scored on the reticle crop's BioCLIP embedding against `labels.npy`. */
sealed interface Goal {
    /** False for the grass tutorial, which skips verify row 1 (R3). */
    val checksHazards: Boolean

    /**
     * Scores a unit [reticle] embedding, with the [full] frame's when the plant gate called the full frame a plant.
     */
    fun score(reticle: FloatArray, full: FloatArray? = null): GoalScore
}

/**
 * PRD verify row 4: the best-scoring row among the hunt's [eligible] species shares the target's genus, so a
 * look-alike in the genus passes, and beats every local toxic or hazard row in [blockers] by at least the margin.
 *
 * With a full-frame plant, each row scores the mean of its reticle and full-frame cosines and the margin is
 * [FULL_MARGIN]; without one, the reticle scores alone against [MARGIN].
 *
 * Scored against the hunt's own rows, never the whole table: on Day 1 the right genus led the whole table on only 60%
 * of crops. Blockers are never targets; a frame one leads, or comes within the margin of, never passes, even inside
 * the target's genus.
 *
 * @param table `species_table.npy`
 * @param genus each table row's genus
 * @param target the target's row
 * @param eligible the hunt's locally eligible species rows, target included
 * @param blockers local toxic-flagged and hazard species rows
 */
class TargetGoal(
    private val table: FloatMatrix,
    private val genus: List<String>,
    private val target: Int,
    private val eligible: IntArray,
    private val blockers: IntArray,
) : Goal {
    init {
        val pool = eligible + blockers
        require(genus.size == table.rows) { "${genus.size} genera for ${table.rows} rows" }
        require(target in eligible) { "target row $target is not eligible" }
        require(pool.distinct().size == pool.size) { "repeated or overlapping pool rows" }
        require(pool.all { it in 0 until table.rows }) { "pool row outside the table" }
        // A pool of one genus has no rival, so any plant would pass.
        require(blockers.isNotEmpty() || eligible.any { genus[it] != genus[target] }) {
            "no row outside the target's genus"
        }
    }

    override val checksHazards = true

    override fun score(reticle: FloatArray, full: FloatArray?): GoalScore {
        fun s(row: Int) = if (full == null) {
            table.dot(row, reticle)
        } else {
            (table.dot(row, reticle) + table.dot(row, full)) / 2
        }
        val margin = if (full == null) MARGIN else FULL_MARGIN
        val scores = eligible.map(::s)
        val best = scores.max()
        val top = eligible[scores.indexOf(best)]
        val blocker = blockers.maxOfOrNull(::s) ?: Double.NEGATIVE_INFINITY
        val score = s(target)
        // A tie for top-1 is no pass, whichever rows tie.
        val met = scores.count { it == best } == 1 && genus[top] == genus[target] && best - blocker >= margin
        return GoalScore(met, score, 1 + scores.count { it > score })
    }

    /** Row 4 constants (`docs/results/day-2/toxic_block.log`, `docs/results/day-5.md`). */
    companion object {
        /**
         * The reticle-only margin, for a frame whose full frame isn't a plant: the smallest round margin that let 0 of
         * 180 local toxic photos pass on Oct 7 (a poison ivy photo read as beautyberry won by 0.0477). Day 5 kept it
         * so that case stays exactly as strict.
         */
        const val MARGIN = 0.048

        /**
         * The margin on the reticle and full-frame mean (day-5 rule R1h): the 0.024 tune boundary plus 0.01 headroom.
         * It let 0 of 267 toxic photos pass and raised target passes from 97 to 116 of 198.
         */
        const val FULL_MARGIN = 0.034
    }
}

/**
 * R3 grass tutorial: grass is in the top [TOP_K] of the fixed tutorial label set.
 *
 * @param labels `labels.npy`
 * @param grass the grass row
 * @param tutorial the 11 fixed tutorial rows, grass included
 */
class TutorialGoal(private val labels: FloatMatrix, private val grass: Int, private val tutorial: IntArray) : Goal {
    init {
        require(grass in tutorial) { "grass row $grass is not a tutorial label" }
        require(tutorial.all { it in 0 until labels.rows }) { "tutorial row outside labels" }
    }

    override val checksHazards = false

    // The tutorial was measured on the reticle alone, so the full frame plays no part.
    override fun score(reticle: FloatArray, full: FloatArray?): GoalScore {
        val score = labels.dot(grass, reticle)
        // A label tied with grass doesn't push it down a rank.
        val rank = 1 + tutorial.count { labels.dot(it, reticle) > score }
        return GoalScore(rank <= TOP_K, score, rank)
    }

    /** Tutorial constants measured on Day 1. */
    companion object {
        /** Grass passes within this rank; on Day 1, top 3 alone passed 50 of 52 CC0 grass photos. */
        const val TOP_K = 3
    }
}
