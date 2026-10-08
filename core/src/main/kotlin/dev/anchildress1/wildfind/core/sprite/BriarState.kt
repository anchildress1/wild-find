package dev.anchildress1.wildfind.core.sprite

/**
 * Briar's states, one sheet each under `assets/briar/`; game events pick the state, and Compose only plays it. Briar
 * stays off hunt pages, so no state reacts to a single capture.
 *
 * @property sheet the sheet's asset name
 * @property holdsLastFrame true when the sheet stops on its last frame instead of handing over to idle
 */
enum class BriarState(val sheet: String, val holdsLastFrame: Boolean = false) {
    /** The safety opener: Briar warns beside a three-leaf plant, then holds that pose. */
    OPENER("opener", holdsLastFrame = true),

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
