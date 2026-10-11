package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LocalListSourceTest {
    private val table = listOf("Quercus", "Acer", "Liquidambar", "Magnolia")
        .mapIndexed { row, genus -> SpeciesRow("$genus s", genus, hazard = false, toxic = false) }
    private val species =
        LocalSpecies(table) { name -> table.indexOfFirst { it.scientific == name }.takeIf { it >= 0 } }
    private val region = RegionKey(34, -85)

    private fun sightings(vararg genera: String) = genera.map { Sighting("$it s", it.lowercase(), 10) }
    private val three = sightings("Quercus", "Acer", "Liquidambar")
    private val one = sightings("Quercus")

    private val asked = mutableListOf<Int>()
    private val saved = mutableMapOf<CacheKey, List<Sighting>>()

    private fun source(pulls: Map<Int, List<Sighting>?>, cache: Map<Int, List<Sighting>> = emptyMap()) =
        LocalListSource(
            species,
            pull = { q: SpeciesCountsQuery ->
                asked += q.radiusKm
                pulls[q.radiusKm]
            },
            cached = { key -> cache[key.radiusKm] },
            save = { key, s ->
                saved[key] = s
                true
            },
        )

    private fun load(source: LocalListSource) = source.load("v1", region, "en", 10)

    @Test
    fun `a fresh pull with enough species plays and is cached`() {
        val result = load(source(mapOf(75 to three)))

        assertEquals(LocalListResult.Ready(species.of(three)), result)
        assertEquals(listOf(75), asked)
        assertEquals(mapOf(CacheKey("v1", region, "en", 10, 75) to three), saved)
    }

    @Test
    fun `with no answer the matching cache plays`() {
        assertEquals(LocalListResult.Ready(species.of(three)), load(source(mapOf(75 to null), mapOf(75 to three))))
        assertEquals(emptyMap<CacheKey, List<Sighting>>(), saved)
    }

    @Test
    fun `with no answer and no cache the place needs signal`() {
        assertEquals(LocalListResult.NeedsSignal, load(source(mapOf(75 to null))))
    }

    @Test
    fun `too few species widen once to 150 km`() {
        assertEquals(LocalListResult.Ready(species.of(three)), load(source(mapOf(75 to one, 150 to three))))
        assertEquals(listOf(75, 150), asked)
    }

    @Test
    fun `still too few after the widen is not enough`() {
        assertEquals(LocalListResult.NotEnough, load(source(mapOf(75 to one, 150 to one))))
    }

    @Test
    fun `a widen with no answer falls back to its own cache, else needs signal`() {
        assertEquals(
            LocalListResult.Ready(species.of(three)),
            load(source(mapOf(75 to one, 150 to null), mapOf(150 to three))),
        )
        assertEquals(LocalListResult.NeedsSignal, load(source(mapOf(75 to one, 150 to null))))
    }

    @ParameterizedTest
    @CsvSource("false,false,true", "true,false,false", "true,true,true")
    fun `only offline requests require a successful save`(requireCached: Boolean, succeeds: Boolean, ready: Boolean) {
        val source = LocalListSource(
            species,
            pull = { query ->
                asked += query.radiusKm
                three
            },
            cached = { null },
            save = { key, sightings ->
                if (succeeds) saved[key] = sightings
                succeeds
            },
        )

        val result = source.load("v1", region, "en", 10, requireCached)

        assertEquals(if (ready) LocalListResult.Ready(species.of(three)) else LocalListResult.NeedsSignal, result)
        assertEquals(listOf(75), asked)
        assertEquals(if (succeeds) 1 else 0, saved.size)
    }

    @ParameterizedTest
    @CsvSource("true,true,true", "true,false,false", "false,true,false", "false,false,false")
    fun `offline widened hunts need both radius entries persisted`(
        nearSaved: Boolean,
        wideSaved: Boolean,
        ready: Boolean,
    ) {
        val source = LocalListSource(
            species,
            pull = { query ->
                asked += query.radiusKm
                if (query.radiusKm == 75) one else three
            },
            cached = { null },
            save = { key, sightings ->
                val succeeds = if (key.radiusKm == 75) nearSaved else wideSaved
                if (succeeds) saved[key] = sightings
                succeeds
            },
        )

        val result = source.load("v1", region, "en", 10, requireCached = true)

        assertEquals(if (ready) LocalListResult.Ready(species.of(three)) else LocalListResult.NeedsSignal, result)
        assertEquals(if (nearSaved) listOf(75, 150) else listOf(75), asked)
    }

    @Test
    fun `failed refreshes retain a previously cached offline hunt`() {
        val source = LocalListSource(
            species,
            pull = { one },
            cached = { key -> three.takeIf { key.radiusKm == 75 } },
            save = { _, _ -> false },
        )

        assertEquals(LocalListResult.Ready(species.of(three)), source.load("v1", region, "en", 10, true))
    }

    @Test
    fun `unexpected cache failures propagate rather than claim success`() {
        val source = LocalListSource(species, pull = { three }, cached = { null }, save = { _, _ -> error("broken") })

        val failure = assertThrows<IllegalStateException> { source.load("v1", region, "en", 10, true) }

        assertEquals("broken", failure.message)
    }
}
