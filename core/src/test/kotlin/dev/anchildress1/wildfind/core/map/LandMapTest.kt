package dev.anchildress1.wildfind.core.map

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LandMapTest {
    // The layout the pipeline's land.pack writes.
    private fun file(version: Int = 1, trailing: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 2 + 4 + 4 + 8 + 4 + trailing).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("WFLD".toByteArray()).put(version.toByte()).put(2)
        buffer.putInt(1).putInt(2).putShort(-18000).putShort(9000).putShort(1234).putShort(0)
        buffer.putInt(0)
        return buffer.array()
    }

    @Test
    fun `parses both levels into degree pairs`() {
        val map = LandMap.parse(file())

        assertEquals(2, map.levels.size)
        assertArrayEquals(floatArrayOf(-180f, 90f, 12.34f, 0f), map.levels[0].single(), 1e-4f)
        assertEquals(0, map.levels[1].size)
    }

    @Test
    fun `refuses another format or a truncated or padded file`() {
        assertThrows<IllegalArgumentException> { LandMap.parse("NOPE".toByteArray() + file().drop(4)) }
        assertThrows<IllegalArgumentException> { LandMap.parse(file(version = 2)) }
        assertThrows<IllegalArgumentException> { LandMap.parse(file(trailing = 2)) }
        assertThrows<java.nio.BufferUnderflowException> { LandMap.parse(file().copyOf(20)) }
    }
}
