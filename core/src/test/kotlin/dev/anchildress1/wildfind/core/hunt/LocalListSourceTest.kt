package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
            save = { key, s -> saved[key] = s },
        )

    private fun load(source: LocalListSource) = source.load("v1", region, "en", 10)

    @Test
    fun `a fresh pull with enough species plays and is cached`() {
        val result = load(source(mapOf(75 to three)))

        assertEquals(LocalListResult.Ready(species.of(three), 75), result)
        assertEquals(listOf(75), asked)
        assertEquals(mapOf(CacheKey("v1", region, "en", 10, 75) to three), saved)
    }

    @Test
    fun `with no answer the matching cache plays`() {
        assertEquals(LocalListResult.Ready(species.of(three), 75), load(source(mapOf(75 to null), mapOf(75 to three))))
        assertEquals(emptyMap<CacheKey, List<Sighting>>(), saved)
    }

    @Test
    fun `with no answer and no cache the place needs signal`() {
        assertEquals(LocalListResult.NeedsSignal, load(source(mapOf(75 to null))))
    }

    @Test
    fun `too few species widen once to 150 km`() {
        assertEquals(LocalListResult.Ready(species.of(three), 150), load(source(mapOf(75 to one, 150 to three))))
        assertEquals(listOf(75, 150), asked)
    }

    @Test
    fun `still too few after the widen is not enough`() {
        assertEquals(LocalListResult.NotEnough(species.of(one)), load(source(mapOf(75 to one, 150 to one))))
    }

    @Test
    fun `a widen with no answer falls back to its own cache, else needs signal`() {
        assertEquals(
            LocalListResult.Ready(species.of(three), 150),
            load(source(mapOf(75 to one, 150 to null), mapOf(150 to three))),
        )
        assertEquals(LocalListResult.NeedsSignal, load(source(mapOf(75 to one, 150 to null))))
    }
}
