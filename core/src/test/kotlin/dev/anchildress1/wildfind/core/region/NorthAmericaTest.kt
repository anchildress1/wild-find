package dev.anchildress1.wildfind.core.region

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NorthAmericaTest {
    @Test
    fun `the box takes in its edges and nothing past them`() {
        listOf(
            RegionKey(5, -80),
            RegionKey(72, -80),
            RegionKey(40, -170),
            RegionKey(40, -50),
        ).forEach { assertTrue(NorthAmerica.contains(it), "$it is on the edge") }
        listOf(
            RegionKey(4, -80),
            RegionKey(73, -80),
            RegionKey(40, -171),
            RegionKey(40, -49),
        ).forEach { assertFalse(NorthAmerica.contains(it), "$it is past the edge") }
    }

    @Test
    fun `west Georgia is in, Tbilisi and London are out`() {
        assertTrue(NorthAmerica.contains(RegionKey(34, -85)))
        assertFalse(NorthAmerica.contains(RegionKey(42, 45)))
        assertFalse(NorthAmerica.contains(RegionKey(51, 0)))
    }
}
