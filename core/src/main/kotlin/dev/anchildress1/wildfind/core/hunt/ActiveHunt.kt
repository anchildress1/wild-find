package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import dev.anchildress1.wildfind.core.verify.TargetGoal

/**
 * The current hunt with the local rows its verify scores against, so a hunt restored after process death or played
 * offline needs no new pull.
 *
 * @property progress tutorial, targets, and finds
 * @property region where the hunt's list came from
 * @property eligible the hunt's locally eligible species rows, targets included
 * @property blockers local toxic-flagged and hazard rows
 */
data class ActiveHunt(
    val progress: HuntProgress,
    val region: RegionKey,
    val eligible: List<Int>,
    val blockers: List<Int>,
) {
    init {
        require(progress.targets.all { it.row in eligible }) { "target outside the eligible rows" }
        require(eligible.none { it in blockers }) { "a blocker is eligible" }
    }

    /** Every local row the hunt knows, for naming what the camera sees. */
    val local: Set<Int> get() = (eligible + blockers).toSet()

    /** Verify row 4's goal for the target at [row], scored over [table] whose rows have [genus]. */
    fun goal(table: FloatMatrix, genus: List<String>, row: Int): TargetGoal =
        TargetGoal(table, genus, row, eligible.toIntArray(), blockers.toIntArray())

    /** Starts a hunt. */
    companion object {
        /** A fresh hunt from [hunt]'s plan over [local], pulled for [region]. */
        fun start(hunt: Hunt, local: LocalList, region: RegionKey): ActiveHunt =
            ActiveHunt(HuntProgress.start(hunt), region, local.eligible.map { it.row }, local.blockers.toList())
    }
}
