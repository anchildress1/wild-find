package dev.anchildress1.wildfind.core.frame

import kotlin.math.min

/** PRD crop geometry, measured on Day 1, in upright (rotation-normalized) frame coordinates. */
object Crops {
    /** Width and height of every model input, in pixels. */
    const val MODEL_SIZE = 224

    /**
     * Default camera analysis width, sensor orientation. With [ANALYSIS_HEIGHT] it is 4:3, and large enough that
     * the visible strip's reticle square on a tall phone still downscales to [MODEL_SIZE], as Day 1's photos did.
     */
    const val ANALYSIS_WIDTH = 1920

    /** Default camera analysis height, sensor orientation. */
    const val ANALYSIS_HEIGHT = 1440

    /** The reticle square's side as a share of the frame's shorter edge. */
    const val RETICLE_SHARE = 0.6

    /** Center square with side equal to the shorter edge. */
    fun fullFrame(width: Int, height: Int): Box = centered(width, height, min(width, height))

    /** Center square with side 60% of the shorter edge, truncated as Day 1's `int(min(size) * 0.6)` did. */
    fun reticle(width: Int, height: Int): Box = centered(width, height, (min(width, height) * RETICLE_SHARE).toInt())

    // Integer halving floors like Day 1's `//`, so odd leftovers put the extra pixel right and below.
    private fun centered(width: Int, height: Int, side: Int) = Box((width - side) / 2, (height - side) / 2, side, side)
}
