package dev.anchildress1.wildfind.core.sprite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpriteSheetTest {
    private val idle = SpriteSheet(frameWidth = 448, frameHeight = 448, frames = 8, columns = 4, fps = 8, loop = true)
    private val second = 1_000_000_000L

    @Test
    fun `steps one frame per fps tick`() {
        assertEquals(listOf(0, 0, 1, 7), listOf(0L, second / 8 - 1, second / 8, second * 7 / 8).map(idle::frameAt))
    }

    @Test
    fun `a loop wraps to the first frame`() {
        assertEquals(0, idle.frameAt(second))
        assertEquals(3, idle.frameAt(second + second * 3 / 8))
    }

    @Test
    fun `a one-shot holds its last frame`() {
        assertEquals(7, idle.copy(loop = false).frameAt(10 * second))
    }

    @Test
    fun `negative elapsed time shows the first frame`() {
        assertEquals(0, idle.frameAt(-5))
    }

    @Test
    fun `offsets walk the grid left to right, then top to bottom`() {
        assertEquals(listOf(0 to 0, 1344 to 0, 0 to 448, 1344 to 448), listOf(0, 3, 4, 7).map(idle::offsetOf))
    }

    @Test
    fun `rejects a sheet with no frames`() {
        assertThrows<IllegalArgumentException> { idle.copy(frames = 0) }
    }
}
