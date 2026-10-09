package dev.anchildress1.wildfind.core.cache

import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HuntCacheTest {
    private val key = CacheKey("1f3c9a07d2e4", RegionKey(34, -85), "en", 10, CacheKey.RADIUS_KM)
    private val entry = CacheEntry(key, listOf(Sighting("Quercus nigra", "water oak", 72)))

    @Test
    fun `an entry serves only the key it was pulled under`() {
        assertEquals(entry.sightings, entry.sightingsFor(key.copy()))
    }

    @Test
    fun `any mismatch discards the entry`() {
        listOf(
            key.copy(schemaVersion = 2),
            key.copy(tableVersion = "000000000000"),
            key.copy(region = RegionKey(34, -84)),
            key.copy(locale = "es"),
            key.copy(month = 11),
            key.copy(radiusKm = CacheKey.WIDE_RADIUS_KM),
        ).forEach { assertNull(entry.sightingsFor(it), "$it") }
    }

    @Test
    fun `table version is the first 12 hex digits of the sha-256`() {
        // SHA-256 of "abc" is ba7816bf8f01cfea414140de5dae2223...
        assertEquals("ba7816bf8f01", CacheKey.tableVersion("abc".toByteArray()))
    }

    @Test
    fun `keys reject impossible months, radii, and blank locales`() {
        assertThrows<IllegalArgumentException> { key.copy(month = 0) }
        assertThrows<IllegalArgumentException> { key.copy(month = 13) }
        assertThrows<IllegalArgumentException> { key.copy(radiusKm = 100) }
        assertThrows<IllegalArgumentException> { key.copy(locale = " ") }
    }
}
