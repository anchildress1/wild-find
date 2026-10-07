package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix

/**
 * How one frame's reticle embedding scored against a goal.
 *
 * @property met true when the frame meets the goal on its own; verify still needs 3 such frames in a row
 * @property score the goal label's cosine score
 * @property rank the goal label's 1-based rank among the scored labels
 */
data class GoalScore(val met: Boolean, val score: Double, val rank: Int)

/** What the current hunt step looks for, scored on the reticle crop's BioCLIP embedding against `labels.npy`. */
sealed interface Goal {
    /** False for the grass tutorial, which skips verify row 1 (R3). */
    val checksHazards: Boolean

    /** Scores a unit reticle [embedding]. */
    fun score(embedding: FloatArray): GoalScore
}

/**
 * PRD verify row 5: the target is top-1 among [candidates], at or above [floor], and past [margin] when set.
 *
 * @param labels `labels.npy`
 * @param target the target's row
 * @param candidates rows scored this frame: the hunt's targets and other locally eligible words, target included
 * @param floor the target's verify_floor; null means no floor until calibration (S50)
 * @param margin the menu's runner-up margin; null means top-1 alone decides
 */
class TargetGoal(
    private val labels: FloatMatrix,
    private val target: Int,
    private val candidates: IntArray,
    private val floor: Double?,
    private val margin: Double?,
) : Goal {
    init {
        require(target in candidates) { "target row $target is not a candidate" }
        // A repeated target row would leave no runner-up and pass any plant; a repeated other row skews the rank.
        require(candidates.distinct().size == candidates.size) { "repeated candidate rows" }
        require(candidates.size >= 2) { "top-1 needs a runner-up" }
        require(candidates.all { it in 0 until labels.rows }) { "candidate row outside labels" }
    }

    override val checksHazards = true

    override fun score(embedding: FloatArray): GoalScore {
        val score = labels.dot(target, embedding)
        var runnerUp = Double.NEGATIVE_INFINITY
        var above = 0
        for (row in candidates) {
            if (row == target) continue
            val other = labels.dot(row, embedding)
            if (other > runnerUp) runnerUp = other
            if (other > score) above++
        }
        val met = score > runnerUp &&
            (floor == null || score >= floor) &&
            (margin == null || score - runnerUp >= margin)
        return GoalScore(met, score, 1 + above)
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

    override fun score(embedding: FloatArray): GoalScore {
        val score = labels.dot(grass, embedding)
        // A label tied with grass doesn't push it down a rank.
        val rank = 1 + tutorial.count { labels.dot(it, embedding) > score }
        return GoalScore(rank <= TOP_K, score, rank)
    }

    /** Tutorial constants measured on Day 1. */
    companion object {
        /** Grass passes within this rank; on Day 1, top 3 alone passed 50 of 52 CC0 grass photos. */
        const val TOP_K = 3
    }
}
