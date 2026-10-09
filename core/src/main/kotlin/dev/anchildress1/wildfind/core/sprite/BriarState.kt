package dev.anchildress1.wildfind.core.sprite

/**
 * Briar's states, one animation each under `assets/briar/`; game events pick the state, and Compose only plays it.
 * On the camera, Briar shows only to warn on the hazard card.
 *
 * @property sheet the animation's asset name; two states may share one
 * @property replayAfterMillis how long the last frame holds before the animation plays again, or null when it loops
 * @property loop the animation repeats with no rest
 */
enum class BriarState(val sheet: String, val replayAfterMillis: Long? = null, val loop: Boolean = false) {
    /** The safety opener: Briar's raised paw as leaves grow around him, beside the leave-it rule, resting between. */
    OPENER("warning", replayAfterMillis = REST_MS),

    /** The hazard card: Briar holds up a paw as leaves grow around him, rests, and warns again. */
    WARNING("warning", replayAfterMillis = REST_MS),

    /** Ready to hunt, the tutorial, and the hunt list: Briar waves hello, rests, and waves again. */
    WELCOME("welcome", replayAfterMillis = REST_MS),

    /** The found screen: Briar cheers, rests, and cheers again, with the hunt-complete cheer. */
    FOUND("complete", replayAfterMillis = REST_MS),

    /** The hunt is complete; its cheer loops. */
    COMPLETE("complete", loop = true),

    /** No signal, or too few plants here: Briar shrugs and turns, ready to try again, rests, and plays again. */
    TRY_AGAIN("try_again", replayAfterMillis = REST_MS),
    ;

    /** The idle loop. */
    companion object {
        /** The loop that plays where no state does. */
        const val IDLE = "idle"
    }
}

// The rest on a sheet's last frame before it replays. A screen keeps one sheet: each source draws Briar at its own
// size (idle stands 472 px tall, welcome 371), so handing over to idle made him jump.
private const val REST_MS = 1_500L
