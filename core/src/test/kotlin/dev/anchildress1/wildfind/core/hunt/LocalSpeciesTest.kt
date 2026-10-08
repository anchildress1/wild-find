package dev.anchildress1.wildfind.core.hunt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalSpeciesTest {
    private val table = listOf(
        SpeciesRow("Quercus nigra", "Quercus", hazard = false, toxic = false),
        SpeciesRow("Acer rubrum", "Acer", hazard = false, toxic = false),
        SpeciesRow("Ilex opaca", "Ilex", hazard = false, toxic = true),
        SpeciesRow("Toxicodendron radicans", "Toxicodendron", hazard = true, toxic = true),
        SpeciesRow("Berberis bealei", "Berberis", hazard = false, toxic = false),
        SpeciesRow("Liquidambar styraciflua", "Liquidambar", hazard = false, toxic = false),
    )
    private val names = table.mapIndexed { row, s -> s.scientific to row }.toMap() + ("Mahonia bealei" to 4)
    private val local = LocalSpecies(table, names::get)

    /** 1,000 sightings in all, so the 0.5% floor is 5. */
    private fun pull(vararg species: Sighting) =
        species.toList() + Sighting("Filler unmatched", "filler", 1000 - species.sumOf { it.count })

    @Test
    fun `a species needs half a percent of the pull's sightings`() {
        val list = local.of(pull(Sighting("Quercus nigra", "water oak", 5), Sighting("Acer rubrum", "red maple", 4)))

        assertEquals(listOf(Eligible(0, "water oak", 5)), list.eligible)
    }

    @Test
    fun `a sparse pull still needs three sightings`() {
        val list = local.of(listOf(Sighting("Quercus nigra", "water oak", 3), Sighting("Acer rubrum", "red maple", 2)))

        assertEquals(listOf(0), list.eligible.map { it.row })
    }

    @Test
    fun `toxic and hazard species never play but block`() {
        val list = local.of(
            pull(
                Sighting("Quercus nigra", "water oak", 50),
                Sighting("Ilex opaca", "American holly", 40),
                Sighting("Toxicodendron radicans", "poison ivy", 30),
            ),
        )

        assertEquals(listOf(0), list.eligible.map { it.row })
        assertArrayEquals(intArrayOf(2, 3), list.blockers)
    }

    @Test
    fun `a toxic species blocks at any sighting count`() {
        val list = local.of(pull(Sighting("Quercus nigra", "water oak", 50), Sighting("Ilex opaca", "holly", 1)))

        assertArrayEquals(intArrayOf(2), list.blockers)
    }

    @Test
    fun `names with no common name, or more than three words, are skipped`() {
        val list = local.of(
            pull(
                Sighting("Quercus nigra", null, 50),
                Sighting("Acer rubrum", "  ", 50),
                Sighting("Liquidambar styraciflua", "American sweet gum tree", 50),
            ),
        )

        assertTrue(list.eligible.isEmpty())
    }

    @Test
    fun `synonyms that resolve to one row sum their sightings`() {
        val list = local.of(
            pull(
                Sighting("Mahonia bealei", "leatherleaf mahonia", 3),
                Sighting("Berberis bealei", "beale's barberry", 2),
            ),
        )

        assertEquals(listOf(Eligible(4, "leatherleaf mahonia", 5)), list.eligible)
    }

    @Test
    fun `eligible species sort by sightings, and fewer than three widens`() {
        val few = local.of(pull(Sighting("Quercus nigra", "water oak", 10), Sighting("Acer rubrum", "red maple", 20)))
        val enough = local.of(
            pull(
                Sighting("Quercus nigra", "water oak", 10),
                Sighting("Acer rubrum", "red maple", 20),
                Sighting("Liquidambar styraciflua", "sweetgum", 10),
            ),
        )

        assertEquals(listOf(1, 0), few.eligible.map { it.row })
        assertTrue(few.needsWiden)
        assertEquals(listOf(1, 0, 5), enough.eligible.map { it.row })
        assertFalse(enough.needsWiden)
    }

    @Test
    fun `an empty pull widens`() {
        assertTrue(local.of(emptyList()).needsWiden)
    }
}
