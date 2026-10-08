package dev.anchildress1.wildfind.core.sprite

/**
 * Briar's states, one sheet each under `assets/briar/`; game events pick the state, and Compose only plays it. Briar
 * stays off hunt pages, so no state reacts to a single capture.
 *
 * @property sheet the sheet's asset name
 * @property replayAfterMillis how long the last frame holds before the sheet plays again, or null to hand over to idle
 */
enum class BriarState(val sheet: String, val replayAfterMillis: Long? = null) {
    /** The safety opener: Briar warns beside a three-leaf plant, again and again with a short rest between. */
    OPENER("opener", replayAfterMillis = OPENER_REST_MS),

    /** First launch, the tutorial, and the hunt list. */
    WELCOME("welcome"),

    /** The found screen. */
    FOUND("found"),

    /** The hunt is complete; its sheet loops. */
    COMPLETE("complete"),
    ;

    /** The idle loop. */
    companion object {
        /** The loop every play-once state sheet hands over to. */
        const val IDLE = "idle"
    }
}

// The opener's rest on its last frame before it replays; long enough to read the pose, short enough to stay alive.
private const val OPENER_REST_MS = 1_500L
