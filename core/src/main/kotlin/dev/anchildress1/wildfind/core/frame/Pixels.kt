package dev.anchildress1.wildfind.core.frame

/**
 * An RGB image as packed `0xAARRGGBB` ints, row after row, the layout of Android's `Bitmap.getPixels`.
 *
 * @property width width in pixels
 * @property height height in pixels
 * @property argb `width * height` pixels; alpha is ignored
 */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height) {
            "expected $width x $height pixels, got ${argb.size}"
        }
    }
}

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFF
private const val OPAQUE = CHANNEL shl 24

/** Red channel of a packed `0xAARRGGBB` pixel. */
fun red(argb: Int): Int = argb shr RED_SHIFT and CHANNEL

/** Green channel of a packed `0xAARRGGBB` pixel. */
fun green(argb: Int): Int = argb shr GREEN_SHIFT and CHANNEL

/** Blue channel of a packed `0xAARRGGBB` pixel. */
fun blue(argb: Int): Int = argb and CHANNEL

/** An opaque packed pixel from 0..255 channels; higher bits of each channel are dropped. */
fun opaque(red: Int, green: Int, blue: Int): Int =
    OPAQUE or (red and CHANNEL shl RED_SHIFT) or (green and CHANNEL shl GREEN_SHIFT) or (blue and CHANNEL)

/**
 * An axis-aligned pixel rectangle.
 *
 * @property left first column
 * @property top first row
 * @property width column count
 * @property height row count
 */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0) { "invalid box $this" }
    }
}
