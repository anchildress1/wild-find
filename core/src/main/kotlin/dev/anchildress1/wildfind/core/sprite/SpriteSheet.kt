package dev.anchildress1.wildfind.core.sprite

/**
 * One sprite sheet per the PRD contract: equal frames in a grid, left to right, then top to bottom.
 *
 * @property frameWidth frame width in pixels
 * @property frameHeight frame height in pixels
 * @property frames frame count
 * @property columns frames per row
 * @property fps playback rate
 * @property loop true to repeat; false holds the last frame
 */
data class SpriteSheet(
    val frameWidth: Int,
    val frameHeight: Int,
    val frames: Int,
    val columns: Int,
    val fps: Int,
    val loop: Boolean,
) {
    init {
        require(frameWidth > 0 && frameHeight > 0 && frames > 0 && columns > 0 && fps > 0) { "invalid sheet: $this" }
    }

    /** Frame shown [elapsedNanos] after playback started. */
    fun frameAt(elapsedNanos: Long): Int {
        val step = elapsedNanos.coerceAtLeast(0) * fps / NANOS_PER_SECOND
        return if (loop) (step % frames).toInt() else step.coerceAtMost(frames - 1L).toInt()
    }

    /** Pixel offset (x, y) of [frame]'s top-left corner in the sheet. */
    fun offsetOf(frame: Int): Pair<Int, Int> = (frame % columns) * frameWidth to (frame / columns) * frameHeight

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
