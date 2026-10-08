package dev.anchildress1.wildfind.core.inat

import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class SpeciesCountsQueryTest {
    private val query = SpeciesCountsQuery(RegionKey(34, -85), 10, "en", CacheKey.RADIUS_KM)

    @Test
    fun `the query sends the region center and the PRD filters`() {
        assertEquals(
            "https://api.inaturalist.org/v1/observations/species_counts?lat=34&lng=-85&radius=75&month=10" +
                "&iconic_taxa=Plantae&quality_grade=research&locale=en&per_page=500&page=2",
            query.url(2),
        )
    }

    @Test
    fun `locales are url-encoded`() {
        assertEquals(true, query.copy(locale = "zh-Hant TW").url(1).contains("locale=zh-Hant+TW&"))
    }

    @Test
    fun `a query never asks past three pages`() {
        assertThrows<IllegalArgumentException> { query.url(0) }
        assertThrows<IllegalArgumentException> { query.url(4) }
        assertEquals(1, SpeciesCountsQuery.pages(0))
        assertEquals(1, SpeciesCountsQuery.pages(500))
        assertEquals(3, SpeciesCountsQuery.pages(1038))
        assertEquals(3, SpeciesCountsQuery.pages(9000))
    }

    @Test
    fun `retry-after reads delay seconds`() {
        assertEquals(30L, SpeciesCountsQuery.retryAfterSeconds(" 30 ", now))
        assertNull(SpeciesCountsQuery.retryAfterSeconds(null, now))
        assertNull(SpeciesCountsQuery.retryAfterSeconds("-1", now))
        assertNull(SpeciesCountsQuery.retryAfterSeconds("soon", now))
    }

    @Test
    fun `retry-after reads an HTTP date as the wait until it, past the 60 second default`() {
        assertEquals(300L, SpeciesCountsQuery.retryAfterSeconds("Wed, 21 Oct 2026 07:33:00 GMT", now))
        assertEquals(0L, SpeciesCountsQuery.retryAfterSeconds("Wed, 21 Oct 2026 07:20:00 GMT", now))
    }

    private val now = Instant.parse("2026-10-21T07:28:00Z")
}
