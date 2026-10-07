package dev.anchildress1.wildfind.core.frame

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pillow's `Image.resize(..., BICUBIC)` for 8-bit RGB, bit for bit.
 *
 * Day 1 measured every model verdict on Pillow crops, and BioCLIP and TinyCLIP shift with the resampler, so the phone
 * reproduces Pillow's two-pass fixed-point convolution exactly (libImaging/Resample.c) instead of using Android's
 * bilinear scaling.
 */
object Bicubic {
    // Resample.c: 32 bits minus 8 for the pixel and 2 for the sign and the summed kernel's headroom.
    private const val PRECISION_BITS = 32 - 8 - 2
    private const val SUPPORT = 2.0
    private const val A = -0.5
    private const val MAX_CHANNEL = 255

    /** Resizes [source] to [width] x [height]. */
    fun resize(source: Pixels, width: Int, height: Int): Pixels {
        require(width > 0 && height > 0) { "invalid size $width x $height" }
        val vertical = Kernel(source.height, height)
        // Pillow runs the horizontal pass only over the rows the vertical pass reads.
        val firstRow = vertical.start[0]
        val lastRow = vertical.start[height - 1] + vertical.count[height - 1]
        val rows = if (width == source.width) {
            source.rowsFrom(firstRow, lastRow)
        } else {
            horizontal(source, firstRow, lastRow, Kernel(source.width, width))
        }
        return if (height == source.height) rows else verticalPass(rows, firstRow, vertical)
    }

    private fun Pixels.rowsFrom(first: Int, last: Int) =
        Pixels(width, last - first, argb.copyOfRange(first * width, last * width))

    private fun horizontal(source: Pixels, firstRow: Int, lastRow: Int, kernel: Kernel): Pixels {
        val out = IntArray(kernel.outSize * (lastRow - firstRow))
        for (y in firstRow until lastRow) {
            val rowStart = y * source.width
            for (x in 0 until kernel.outSize) {
                out[(y - firstRow) * kernel.outSize + x] =
                    kernel.apply(x) { i -> source.argb[rowStart + kernel.start[x] + i] }
            }
        }
        return Pixels(kernel.outSize, lastRow - firstRow, out)
    }

    private fun verticalPass(rows: Pixels, firstRow: Int, kernel: Kernel): Pixels {
        val out = IntArray(rows.width * kernel.outSize)
        for (y in 0 until kernel.outSize) {
            val top = kernel.start[y] - firstRow
            for (x in 0 until rows.width) {
                out[y * rows.width + x] = kernel.apply(y) { i -> rows.argb[(top + i) * rows.width + x] }
            }
        }
        return Pixels(rows.width, kernel.outSize, out)
    }

    /** Per-output-pixel source span and fixed-point weights for one axis, as Resample.c's precompute_coeffs. */
    // Pillow's literals stay literal so this reads line for line against Resample.c.
    @Suppress("MagicNumber")
    private class Kernel(inSize: Int, val outSize: Int) {
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        private val size: Int
        private val weights: IntArray

        init {
            val scale = inSize.toDouble() / outSize
            val filterScale = max(scale, 1.0)
            val support = SUPPORT * filterScale
            val inverseScale = 1.0 / filterScale
            size = ceil(support).toInt() * 2 + 1
            weights = IntArray(outSize * size)
            val prekk = DoubleArray(size)
            for (xx in 0 until outSize) {
                val center = (xx + 0.5) * scale
                // C's (int) cast truncates toward zero; Kotlin's toInt matches it.
                val xmin = max((center - support + 0.5).toInt(), 0)
                val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
                var total = 0.0
                for (x in 0 until xmax) {
                    prekk[x] = filter((x + xmin - center + 0.5) * inverseScale)
                    total += prekk[x]
                }
                for (x in 0 until xmax) {
                    val k = if (total != 0.0) prekk[x] / total else prekk[x]
                    weights[xx * size + x] = if (k < 0) {
                        (-0.5 + k * (1 shl PRECISION_BITS)).toInt()
                    } else {
                        (0.5 + k * (1 shl PRECISION_BITS)).toInt()
                    }
                }
                start[xx] = xmin
                count[xx] = xmax
            }
        }

        inline fun apply(out: Int, pixel: (Int) -> Int): Int {
            var r = 1 shl (PRECISION_BITS - 1)
            var g = r
            var b = r
            for (i in 0 until count[out]) {
                val p = pixel(i)
                val k = weights[out * size + i]
                r += red(p) * k
                g += green(p) * k
                b += blue(p) * k
            }
            return opaque(clip(r), clip(g), clip(b))
        }

        private fun clip(sum: Int) = (sum shr PRECISION_BITS).coerceIn(0, MAX_CHANNEL)

        private fun filter(distance: Double): Double {
            val x = if (distance < 0) -distance else distance
            return when {
                x < 1.0 -> ((A + 2.0) * x - (A + 3.0)) * x * x + 1
                x < 2.0 -> (((x - 5) * x + 8) * x - 4) * A
                else -> 0.0
            }
        }
    }
}
