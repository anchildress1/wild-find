package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AreaCacherTest {
    private val table = listOf("Quercus", "Acer", "Liquidambar").map {
        SpeciesRow("$it s", it, hazard = false, toxic = false)
    }
    private val species =
        LocalSpecies(table) { name -> table.indexOfFirst { it.scientific == name }.takeIf { it >= 0 } }
    private val region = RegionKey(34, -85)
    private val three = listOf("Quercus", "Acer", "Liquidambar").map { Sighting("$it s", it.lowercase(), 10) }

    private val asked = mutableListOf<Int>()
    private val saved = mutableMapOf<CacheKey, List<Sighting>>()
    private var pauses = 0
    private val progress = mutableListOf<Int>()

    // iNat answers every month in [answering], and no month after.
    private fun cacher(answering: Set<Int>) = AreaCacher(
        LocalListSource(
            species,
            pull = { q: SpeciesCountsQuery ->
                asked += q.month
                three.takeIf { q.month in answering }
            },
            cached = { null },
            save = { key, sightings -> saved[key] = sightings },
        ),
    )

    private fun run(answering: Set<Int>) =
        cacher(answering).cache("v1", region, "en", pause = { pauses++ }, progress = { progress += it })

    @Test
    fun `every month is pulled and cached under the key a hunt asks for`() {
        val done = run((1..12).toSet())

        assertEquals(12, done)
        assertEquals((1..12).toList(), asked)
        assertEquals((1..12).map { CacheKey("v1", region, "en", it, CacheKey.RADIUS_KM) }.toSet(), saved.keys)
        assertEquals((1..12).toList(), progress)
    }

    @Test
    fun `it pauses between months but not after the last`() {
        run((1..12).toSet())

        assertEquals(11, pauses)
    }

    @Test
    fun `it stops at the first month nothing answers and reports how many are saved`() {
        val done = run((1..5).toSet())

        assertEquals(5, done)
        assertEquals((1..6).toList(), asked)
        assertEquals(listOf(1, 2, 3, 4, 5), progress)
    }

    @Test
    fun `a month already cached counts even when iNat stays quiet`() {
        val cachedMonth3 = AreaCacher(
            LocalListSource(
                species,
                pull = { null },
                cached = { key -> three.takeIf { key.month <= 3 } },
                save = { _, _ -> },
            ),
        )

        assertEquals(3, cachedMonth3.cache("v1", region, "en", pause = {}, progress = {}))
    }
}
