package dev.anchildress1.wildfind.core.frame

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.ByteBuffer

class FrameTest {
    // 3 x 2, labelled by value so every orientation is checkable by eye.
    private val grid = Pixels(3, 2, intArrayOf(1, 2, 3, 4, 5, 6).map { OPAQUE or it }.toIntArray())

    @ParameterizedTest(name = "rotation {0}")
    @CsvSource(
        "0, 3, 2, 1 2 3 4 5 6",
        "90, 2, 3, 4 1 5 2 6 3",
        "180, 3, 2, 6 5 4 3 2 1",
        "270, 2, 3, 3 6 2 5 1 4",
    )
    fun `upright reads the frame turned clockwise by its rotation`(rotation: Int, w: Int, h: Int, expected: String) {
        val frame = cameraFrame(grid, rotation, left = 1, top = 2, padding = 4)

        val pixels = frame.upright(Box(0, 0, w, h))

        assertEquals(w to h, frame.width to frame.height)
        assertArrayEquals(expected.split(" ").map { OPAQUE or it.toInt() }.toIntArray(), pixels.argb)
    }

    @Test
    fun `upright copies only the asked box`() {
        val frame = cameraFrame(grid, 90)

        assertArrayEquals(intArrayOf(OPAQUE or 5, OPAQUE or 6), frame.upright(Box(0, 1, 1, 2)).argb)
    }

    @Test
    fun `a box outside the upright frame is rejected`() {
        assertThrows<IllegalArgumentException> { cameraFrame(grid, 90).upright(Box(0, 0, 3, 2)) }
    }

    @Test
    fun `the last row may end without stride padding`() {
        val buffer = ByteBuffer.allocate(16 * 1 + 8)

        assertEquals(2, RgbaFrame(buffer, 16, Box(0, 0, 2, 2), 0).width)
    }

    @Test
    fun `frames reject bad rotations, short strides, and short buffers`() {
        val buffer = ByteBuffer.allocate(64)
        assertThrows<IllegalArgumentException> { RgbaFrame(buffer, 8, Box(0, 0, 2, 2), 45) }
        assertThrows<IllegalArgumentException> { RgbaFrame(buffer, 4, Box(0, 0, 2, 2), 0) }
        assertThrows<IllegalArgumentException> { RgbaFrame(buffer, 32, Box(0, 0, 2, 3), 0) }
    }

    @Test
    fun `crop geometry floors the centering and truncates the reticle side like Day 1`() {
        assertEquals(Box(41, 0, 250, 250), Crops.fullFrame(333, 250))
        assertEquals(Box(91, 50, 150, 150), Crops.reticle(333, 250))
        assertEquals(Box(0, 80, 480, 480), Crops.fullFrame(480, 640))
        assertEquals(Box(96, 176, 288, 288), Crops.reticle(480, 640))
    }

    @Test
    fun `resizing to the same size copies the image`() {
        val same = Bicubic.resize(grid, 3, 2)

        assertArrayEquals(grid.argb.map { it or OPAQUE }.toIntArray(), same.argb)
    }

    @Test
    fun `a uniform image stays uniform in both directions`() {
        val gray = Pixels(5, 4, IntArray(20) { OPAQUE or 0x80_80_80 })

        assertEquals(setOf(OPAQUE or 0x80_80_80), Bicubic.resize(gray, 9, 2).argb.toSet())
    }

    @Test
    fun `resize rejects an empty target`() {
        assertThrows<IllegalArgumentException> { Bicubic.resize(grid, 0, 2) }
    }

    @Test
    fun `pixels and boxes validate their sizes`() {
        assertThrows<IllegalArgumentException> { Pixels(2, 2, IntArray(3)) }
        assertThrows<IllegalArgumentException> { Box(-1, 0, 1, 1) }
        assertThrows<IllegalArgumentException> { Box(0, 0, 0, 1) }
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
    }
}
