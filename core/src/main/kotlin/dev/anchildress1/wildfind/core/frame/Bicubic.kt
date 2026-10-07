package dev.anchildress1.wildfind.core.frame

import java.util.stream.IntStream
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
        val rows = horizontal(
            source,
            firstRow,
            lastRow,
            if (width ==
                source.width
            ) {
                null
            } else {
                Kernel(source.width, width)
            },
        )
        return (if (height == source.height) rows else verticalPass(rows, vertical, firstRow)).pack()
    }

    /** One 8-bit channel per array, row after row, so each tap reads one int instead of unpacking a pixel. */
    private class Planes(val width: Int, val height: Int) {
        val r = IntArray(width * height)
        val g = IntArray(width * height)
        val b = IntArray(width * height)

        fun pack() = Pixels(width, height, IntArray(width * height) { opaque(r[it], g[it], b[it]) })
    }

    // Rows are independent, so they spread across cores; each writes only its own cells, so the result is identical
    // to a serial run, and integer sums are exact in any order. A null kernel copies rows unchanged, as Pillow skips
    // a pass whose size doesn't change. Arrays are read into locals: the JIT won't hoist field reads out of loops.
    private fun horizontal(source: Pixels, firstRow: Int, lastRow: Int, kernel: Kernel?): Planes {
        val out = Planes(kernel?.outSize ?: source.width, lastRow - firstRow)
        val argb = source.argb
        val inWidth = source.width
        val outWidth = out.width
        val (outR, outG, outB) = Triple(out.r, out.g, out.b)
        IntStream.range(0, out.height).parallel().forEach { y ->
            val inRow = (firstRow + y) * inWidth
            val outRow = y * outWidth
            if (kernel == null) {
                for (x in 0 until outWidth) {
                    val p = argb[inRow + x]
                    outR[outRow + x] = red(p)
                    outG[outRow + x] = green(p)
                    outB[outRow + x] = blue(p)
                }
                return@forEach
            }
            val r = IntArray(inWidth)
            val g = IntArray(inWidth)
            val b = IntArray(inWidth)
            for (x in 0 until inWidth) {
                val p = argb[inRow + x]
                r[x] = red(p)
                g[x] = green(p)
                b[x] = blue(p)
            }
            val start = kernel.start
            val count = kernel.count
            val weights = kernel.weights
            val size = kernel.size
            for (x in 0 until outWidth) {
                val base = start[x]
                val w = x * size
                var sr = HALF
                var sg = HALF
                var sb = HALF
                for (i in 0 until count[x]) {
                    val k = weights[w + i]
                    sr += r[base + i] * k
                    sg += g[base + i] * k
                    sb += b[base + i] * k
                }
                outR[outRow + x] = clip(sr)
                outG[outRow + x] = clip(sg)
                outB[outRow + x] = clip(sb)
            }
        }
        return out
    }

    // Accumulates whole source rows into each output row, so every inner loop walks memory in order.
    private fun verticalPass(rows: Planes, kernel: Kernel, firstRow: Int): Planes {
        val out = Planes(rows.width, kernel.outSize)
        val width = rows.width
        val (inR, inG, inB) = Triple(rows.r, rows.g, rows.b)
        val (outR, outG, outB) = Triple(out.r, out.g, out.b)
        val start = kernel.start
        val count = kernel.count
        val weights = kernel.weights
        val size = kernel.size
        IntStream.range(0, kernel.outSize).parallel().forEach { y ->
            val r = IntArray(width) { HALF }
            val g = IntArray(width) { HALF }
            val b = IntArray(width) { HALF }
            val top = start[y] - firstRow
            for (i in 0 until count[y]) {
                val k = weights[y * size + i]
                val inRow = (top + i) * width
                for (x in 0 until width) {
                    r[x] += inR[inRow + x] * k
                    g[x] += inG[inRow + x] * k
                    b[x] += inB[inRow + x] * k
                }
            }
            val outRow = y * width
            for (x in 0 until width) {
                outR[outRow + x] = clip(r[x])
                outG[outRow + x] = clip(g[x])
                outB[outRow + x] = clip(b[x])
            }
        }
        return out
    }

    // Resample.c starts each sum at half a unit, so the shift rounds instead of truncating.
    private const val HALF = 1 shl (PRECISION_BITS - 1)

    private fun clip(sum: Int) = (sum shr PRECISION_BITS).coerceIn(0, MAX_CHANNEL)

    /** Per-output-pixel source span and fixed-point weights for one axis, as Resample.c's precompute_coeffs. */
    // Pillow's literals stay literal so this reads line for line against Resample.c.
    @Suppress("MagicNumber")
    private class Kernel(inSize: Int, val outSize: Int) {
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val size: Int
        val weights: IntArray

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
