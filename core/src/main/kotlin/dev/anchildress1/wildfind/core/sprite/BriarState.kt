package dev.anchildress1.wildfind.core.sprite

/**
 * Briar's states, one sheet each under `assets/briar/`; game events pick the state, and Compose only plays it. Briar
 * stays off hunt pages, so no state reacts to a single capture.
 *
 * @property sheet the sheet's asset name
 */
enum class BriarState(val sheet: String) {
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
