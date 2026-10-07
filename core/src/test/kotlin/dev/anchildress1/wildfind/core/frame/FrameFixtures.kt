package dev.anchildress1.wildfind.core.frame

import java.nio.ByteBuffer
import javax.imageio.ImageIO

/** Reads a PNG from the `crops/` test resources written by `make crop-reference`. */
fun png(name: String): Pixels {
    val image = requireNotNull(object {}.javaClass.getResourceAsStream("/crops/$name")) { "missing $name" }
        .use(ImageIO::read)
    return Pixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
}

/**
 * Lays [pixels] into an RGBA buffer the way a camera hands it over: noise [left] pixels before and [top] rows above
 * the visible region and as much again after it, plus [padding] spare bytes at the end of each row.
 */
fun cameraFrame(pixels: Pixels, rotationDegrees: Int, left: Int = 0, top: Int = 0, padding: Int = 0): RgbaFrame {
    val width = pixels.width + 2 * left
    val height = pixels.height + 2 * top
    val stride = width * 4 + padding
    val buffer = ByteBuffer.allocateDirect(stride * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val inside = x in left until left + pixels.width && y in top until top + pixels.height
            val argb = if (inside) pixels.argb[(y - top) * pixels.width + (x - left)] else NOISE * (x + y)
            val i = y * stride + x * 4
            buffer.put(i, (argb shr 16).toByte())
            buffer.put(i + 1, (argb shr 8).toByte())
            buffer.put(i + 2, argb.toByte())
            buffer.put(i + 3, (argb ushr 24).toByte())
        }
    }
    return RgbaFrame(buffer, stride, Box(left, top, pixels.width, pixels.height), rotationDegrees)
}

/** Equal when every pixel's RGB matches; alpha is ignored as the encoders ignore it. */
fun sameRgb(a: Pixels, b: Pixels): Boolean =
    a.width == b.width && a.height == b.height && a.argb.indices.all { (a.argb[it] xor b.argb[it]) and RGB == 0 }

private const val NOISE = 0x0103_0507
private const val RGB = 0x00FF_FFFF
