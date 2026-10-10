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
            cached = saved::get,
            save = { key, sightings ->
                saved[key] = sightings
                true
            },
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
                save = { _, _ -> false },
            ),
        )

        assertEquals(3, cachedMonth3.cache("v1", region, "en", pause = {}, progress = {}))
    }

    @Test
    fun `an online answer without a durable save never counts as offline`() {
        val cacher = AreaCacher(LocalListSource(species, pull = { three }, cached = { null }, save = { _, _ -> false }))

        assertEquals(0, cacher.cache("v1", region, "en", pause = { pauses++ }, progress = { progress += it }))
        assertEquals(emptyList<Int>(), progress)
        assertEquals(0, pauses)
    }

    @Test
    fun `a durable sparse month counts even when it cannot offer a hunt`() {
        val sparse = three.take(1)
        val cacher = AreaCacher(
            LocalListSource(
                species,
                pull = { query ->
                    asked += query.radiusKm
                    sparse
                },
                cached = saved::get,
                save = { key, sightings ->
                    saved[key] = sightings
                    true
                },
            ),
        )

        assertEquals(12, cacher.cache("v1", region, "en", pause = {}, progress = { progress += it }))
        assertEquals(24, asked.size)
        assertEquals(24, saved.size)
        assertEquals((1..12).toList(), progress)
    }

    @Test
    fun `a write failure stops after the last durable month`() {
        val cacher = AreaCacher(
            LocalListSource(
                species,
                pull = { query ->
                    asked += query.month
                    three
                },
                cached = saved::get,
                save = { key, sightings ->
                    if (key.month <= 5) saved[key] = sightings
                    key.month <= 5
                },
            ),
        )

        assertEquals(5, cacher.cache("v1", region, "en", pause = { pauses++ }, progress = { progress += it }))
        assertEquals((1..6).toList(), asked)
        assertEquals((1..5).toList(), progress)
        assertEquals(5, saved.size)
    }
}
