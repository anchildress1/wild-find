package dev.anchildress1.wildfind.core.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionKeyTest {
    @Test
    fun `carrollton rounds into west georgia`() {
        val key = RegionKey.from(33.58, -85.08)

        assertEquals(RegionKey.WEST_GEORGIA, key)
        assertTrue(key.isSupported)
    }

    @Test
    fun `atlanta rounds to a neighboring unsupported region`() {
        val key = RegionKey.from(33.75, -84.39)

        assertEquals(RegionKey(34, -84), key)
        assertFalse(key.isSupported)
    }

    @Test
    fun `point inside the 75 km radius but south of 33_5 is unsupported`() {
        assertFalse(RegionKey.from(33.4, -85.0).isSupported)
    }

    @Test
    fun `half-degree ties round toward positive infinity`() {
        assertEquals(RegionKey(34, -85), RegionKey.from(33.5, -85.5))
    }

    @Test
    fun `key string matches the cache and data contract format`() {
        assertEquals("34_-85", RegionKey.WEST_GEORGIA.toString())
    }
}
