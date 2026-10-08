package dev.anchildress1.wildfind.core.sprite

import dev.anchildress1.wildfind.core.verify.Verdict

/**
 * Briar's states, one sheet each under `assets/briar/`; game events pick the state, and Compose only plays it.
 *
 * @property sheet the sheet's asset name
 */
enum class BriarState(val sheet: String) {
    /** First launch and the opener. */
    WELCOME("welcome"),

    /** A hunt or target starts. */
    SEARCHING("searching"),

    /** Verify found the target. */
    FOUND("found"),

    /** A capture missed or warned. */
    RETRY("retry"),

    /** The hunt is complete. */
    COMPLETE("complete"),
    ;

    /** Picks the state for a capture's outcome. */
    companion object {
        /** The loop every state sheet hands over to once it has played. */
        const val IDLE = "idle"

        /** Briar's reaction to a capture's final verdict, or null while the streak is still matching. */
        fun after(verdict: Verdict): BriarState? = when (verdict) {
            Verdict.Found -> FOUND
            is Verdict.Matching -> null
            Verdict.Hazard, Verdict.NotPlant, Verdict.TapToFocus, Verdict.WalkCloser, Verdict.Guide -> RETRY
        }
    }
}
