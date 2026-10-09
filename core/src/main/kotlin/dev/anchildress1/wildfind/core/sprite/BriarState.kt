package dev.anchildress1.wildfind.core.sprite

/**
 * Briar's states, one animation each under `assets/briar/`; game events pick the state, and Compose only plays it.
 * Briar stays off hunt pages, so no state reacts to a single capture.
 *
 * @property sheet the animation's asset name; two states may share one
 * @property replayAfterMillis how long the last frame holds before the animation plays again, or null to hand over
 * to idle
 * @property loop the animation repeats with no rest
 */
enum class BriarState(val sheet: String, val replayAfterMillis: Long? = null, val loop: Boolean = false) {
    /** The safety opener: Briar warns beside a three-leaf plant, again and again with a short rest between. */
    OPENER("opener", replayAfterMillis = REST_MS),

    /** First launch, the tutorial, and the hunt list: Briar waves, rests, and waves again. */
    WELCOME("welcome", replayAfterMillis = REST_MS),

    /** The found screen: Briar cheers, rests, and cheers again, with the hunt-complete cheer. */
    FOUND("complete", replayAfterMillis = REST_MS),

    /** The hunt is complete; its cheer loops. */
    COMPLETE("complete", loop = true),
    ;

    /** The idle loop. */
    companion object {
        /** The loop every play-once state sheet hands over to. */
        const val IDLE = "idle"
    }
}

// The rest on a sheet's last frame before it replays. A screen keeps one sheet: each source draws Briar at its own
// size (idle stands 472 px tall, welcome 371), so handing over to idle made him jump.
private const val REST_MS = 1_500L
