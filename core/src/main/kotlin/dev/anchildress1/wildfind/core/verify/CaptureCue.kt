package dev.anchildress1.wildfind.core.verify

/** What the kid sees after one capture, from its last frame's verdict (PRD verify table). */
enum class CaptureCue {
    /** Row 1: the hazard card. */
    HAZARD,

    /** Row 2, nothing in view is a plant: "Point the camera at a plant". */
    POINT_AT_PLANT,

    /** Row 2, a plant is in view but not in the ring: "Put the plant in the circle". */
    PUT_IN_CIRCLE,

    /** Row 3: "Tap the plant to focus". */
    TAP_TO_FOCUS,

    /** Row 4: the Found screen. */
    FOUND,

    /** Row 5: "Get closer or zoom in". */
    GET_CLOSER,

    /** Row 6: "Keep looking for" the target, never naming the plant in view. */
    KEEP_LOOKING,
    ;

    /** Maps a verdict. */
    companion object {
        /** The cue for a capture's final [verdict]; null while the streak is still matching. */
        fun of(verdict: Verdict, fullFramePlant: Boolean): CaptureCue? = when (verdict) {
            Verdict.Hazard -> HAZARD
            Verdict.NotPlant -> if (fullFramePlant) PUT_IN_CIRCLE else POINT_AT_PLANT
            Verdict.TapToFocus -> TAP_TO_FOCUS
            Verdict.Found -> FOUND
            Verdict.WalkCloser -> GET_CLOSER
            Verdict.Guide -> KEEP_LOOKING
            is Verdict.Matching -> null
        }
    }
}
