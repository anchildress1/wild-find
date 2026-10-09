package dev.anchildress1.wildfind.core.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegionKeyTest {
    @Test
    fun `places anywhere round to their whole-degree region`() {
        assertEquals(RegionKey(34, -85), RegionKey.from(33.58, -85.08))
        assertEquals(RegionKey(34, -84), RegionKey.from(33.75, -84.39))
        assertEquals(RegionKey(-34, 151), RegionKey.from(-33.87, 151.21))
    }

    @Test
    fun `both sides of the antimeridian share one key`() {
        assertEquals(RegionKey(52, -180), RegionKey.from(52.0, 179.6))
        assertEquals(RegionKey(52, -180), RegionKey.from(52.0, -179.6))
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
