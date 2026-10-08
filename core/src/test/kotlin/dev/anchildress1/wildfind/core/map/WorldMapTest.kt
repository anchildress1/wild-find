package dev.anchildress1.wildfind.core.map

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorldMapTest {
    // The layout the pipeline's world_map.pack writes: a world land ring and an empty area state layer.
    private fun file(version: Int = 1, kind: Int = 0, trailing: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(6 + 6 + 4 + 8 + 6 + trailing).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("WFMP".toByteArray()).put(version.toByte()).put(2)
        buffer.put(kind.toByte()).put(0).putInt(1).putInt(2)
        buffer.putShort(-18000).putShort(9000).putShort(1234).putShort(0)
        buffer.put(2).put(1).putInt(0)
        return buffer.array()
    }

    @Test
    fun `parses each layer at its level into degree pairs`() {
        val map = WorldMap.parse(file())

        assertArrayEquals(floatArrayOf(-180f, 90f, 12.34f, 0f), map.shapes(MapLayer.LAND, 0).single(), 1e-4f)
        assertTrue(map.shapes(MapLayer.STATES, 1).isEmpty())
        assertTrue(map.shapes(MapLayer.BORDERS, 0).isEmpty())
        assertEquals(setOf(MapLayer.LAND to 0, MapLayer.STATES to 1), map.layers.keys)
    }

    @Test
    fun `refuses another format, an unknown layer, or a truncated or padded file`() {
        assertThrows<IllegalArgumentException> { WorldMap.parse("NOPE".toByteArray() + file().drop(4)) }
        assertThrows<IllegalArgumentException> { WorldMap.parse(file(version = 2)) }
        assertThrows<IllegalArgumentException> { WorldMap.parse(file(kind = 9)) }
        assertThrows<IllegalArgumentException> { WorldMap.parse(file(trailing = 2)) }
        assertThrows<BufferUnderflowException> { WorldMap.parse(file().copyOf(20)) }
    }
}
