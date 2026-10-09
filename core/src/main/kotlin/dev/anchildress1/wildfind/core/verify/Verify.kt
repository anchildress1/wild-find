package dev.anchildress1.wildfind.core.verify

/**
 * What one analyzed frame showed, as the PRD verify table reads it.
 *
 * @property hazard row 1: a hazard species ranked in the top 5 in a region the plant gate called a plant
 * @property reticlePlant row 2: the plant gate called the reticle crop a plant
 * @property focus row 3: this frame's own autofocus reading, or null when it has none; distance only explains a
 *   miss
 * @property goalMet row 4: this frame alone meets the goal
 */
data class FrameEvidence(val hazard: Boolean, val reticlePlant: Boolean, val focus: Focus?, val goalMet: Boolean)

/**
 * One frame's outcome under the verify table; the first matching row wins.
 *
 * Distance never blocks a match: the Oct 7 field runs showed the close-range rule stopping 51% of analyzed frames.
 */
sealed interface Verdict {
    /** Row 1: warn, no star. */
    data object Hazard : Verdict

    /** Row 2: "Point the camera at a plant". */
    data object NotPlant : Verdict

    /** Row 3: "Tap the plant to focus". */
    data object TapToFocus : Verdict

    /**
     * Row 4 holding, before the streak completes: "Hold still".
     *
     * @property frames consecutive matching frames so far, 1 to [VerifyStreak.FRAMES] - 1
     */
    data class Matching(val frames: Int) : Verdict

    /** Row 4 complete: keep this frame's reticle crop, then Found. */
    data object Found : Verdict

    /** Row 5: no match and the subject is far: "Get closer or zoom in". */
    data object WalkCloser : Verdict

    /** Row 6: "Keep looking for" the target. */
    data object Guide : Verdict
}

/** Applies the verify table to consecutive frames and counts row 4's streak; one instance per target. */
class VerifyStreak {
    private var run = 0

    /** The verdict for the next analyzed frame. */
    fun next(frame: FrameEvidence): Verdict {
        val verdict = when {
            frame.hazard -> Verdict.Hazard
            !frame.reticlePlant -> Verdict.NotPlant
            frame.focus?.isFocused != true -> Verdict.TapToFocus
            frame.goalMet -> if (run + 1 == FRAMES) Verdict.Found else Verdict.Matching(run + 1)
            !frame.focus.isClose -> Verdict.WalkCloser
            else -> Verdict.Guide
        }
        // A Found starts a fresh streak, so one target can take another capture (R12).
        run = if (verdict is Verdict.Matching) verdict.frames else 0
        return verdict
    }

    /** Verify constants from the PRD. */
    companion object {
        /** Consecutive matching frames before Found. */
        const val FRAMES = 3
    }
}
