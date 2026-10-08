package dev.anchildress1.wildfind.core.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegionKeyTest {
    @Test
    fun `carrollton rounds into west georgia`() {
        assertEquals(RegionKey(34, -85), RegionKey.from(33.58, -85.08))
    }

    @Test
    fun `atlanta rounds to its own neighboring region`() {
        assertEquals(RegionKey(34, -84), RegionKey.from(33.75, -84.39))
    }

    @Test
    fun `places anywhere get a region, tbilisi included`() {
        assertEquals(RegionKey(42, 45), RegionKey.from(41.72, 44.79))
        assertEquals(RegionKey(-34, 151), RegionKey.from(-33.87, 151.21))
    }

    @Test
    fun `half-degree ties round toward positive infinity`() {
        assertEquals(RegionKey(34, -85), RegionKey.from(33.5, -85.5))
        assertEquals(RegionKey(-33, -85), RegionKey.from(-33.5, -85.4))
    }

    @Test
    fun `key string matches the cache and data contract format`() {
        assertEquals("34_-85", RegionKey(34, -85).toString())
    }
}
