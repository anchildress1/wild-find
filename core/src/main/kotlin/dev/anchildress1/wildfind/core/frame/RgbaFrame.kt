package dev.anchildress1.wildfind.core.frame

import java.nio.ByteBuffer

/**
 * One RGBA_8888 camera analysis frame, read in upright coordinates without rotating the whole buffer.
 *
 * It wraps the camera's buffer without copying, so it is valid only until the source image is closed.
 *
 * @param buffer RGBA bytes; row `r` starts at `r * rowStride`
 * @param rowStride bytes per buffer row, which may exceed `4 * width`
 * @param visible the region the preview shows (CameraX's shared-viewport crop rect), in buffer coordinates
 * @param rotationDegrees clockwise rotation that makes the frame upright: 0, 90, 180, or 270
 */
class RgbaFrame(
    private val buffer: ByteBuffer,
    private val rowStride: Int,
    private val visible: Box,
    private val rotationDegrees: Int,
) {
    init {
        require(rotationDegrees in ROTATIONS) { "rotation $rotationDegrees" }
        require(rowStride >= (visible.left + visible.width) * BYTES_PER_PIXEL) { "row stride $rowStride too short" }
        // The last row may stop right after its pixels, without the stride's padding.
        val end =
            (visible.top + visible.height - 1).toLong() * rowStride + (visible.left + visible.width) * BYTES_PER_PIXEL
        require(buffer.limit() >= end) { "buffer of ${buffer.limit()} bytes ends before $end" }
    }

    private val sideways = rotationDegrees == QUARTER || rotationDegrees == THREE_QUARTERS

    /** Width of the upright visible frame. */
    val width: Int = if (sideways) visible.height else visible.width

    /** Height of the upright visible frame. */
    val height: Int = if (sideways) visible.width else visible.height

    /** Copies [box], given in upright coordinates, as upright pixels. */
    fun upright(box: Box): Pixels {
        require(box.left + box.width <= width && box.top + box.height <= height) { "$box outside $width x $height" }
        val source = sourceBox(box)
        // One bulk read per source row; per-byte reads from a direct buffer cost far more at camera frame rates.
        // Relative reads on a duplicate: the absolute bulk get(index, dst, ...) needs API 35, above minSdk.
        val reader = buffer.duplicate()
        val rowBytes = source.width * BYTES_PER_PIXEL
        val bytes = ByteArray(rowBytes * source.height)
        for (row in 0 until source.height) {
            reader.position(
                (visible.top + source.top + row) * rowStride + (visible.left + source.left) * BYTES_PER_PIXEL,
            )
            reader.get(bytes, row * rowBytes, rowBytes)
        }
        val w = box.width
        val h = box.height
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = BYTES_PER_PIXEL * when (rotationDegrees) {
                    0 -> y * source.width + x
                    QUARTER -> (w - 1 - x) * source.width + y
                    HALF -> (h - 1 - y) * source.width + (w - 1 - x)
                    else -> x * source.width + (h - 1 - y)
                }
                argb[y * w + x] = opaque(bytes[i].toInt(), bytes[i + 1].toInt(), bytes[i + 2].toInt())
            }
        }
        return Pixels(box.width, box.height, argb)
    }

    /** The visible-region rectangle that rotates onto the upright [box]. */
    private fun sourceBox(box: Box): Box = when (rotationDegrees) {
        0 -> box
        QUARTER -> Box(box.top, visible.height - box.left - box.width, box.height, box.width)
        HALF -> Box(visible.width - box.left - box.width, visible.height - box.top - box.height, box.width, box.height)
        else -> Box(visible.width - box.top - box.height, box.left, box.height, box.width)
    }

    private companion object {
        const val QUARTER = 90
        const val HALF = 180
        const val THREE_QUARTERS = 270
        val ROTATIONS = setOf(0, QUARTER, HALF, THREE_QUARTERS)
        const val BYTES_PER_PIXEL = 4
    }
}
