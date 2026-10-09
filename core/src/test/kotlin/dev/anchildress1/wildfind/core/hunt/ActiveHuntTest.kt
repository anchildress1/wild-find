package dev.anchildress1.wildfind.core.hunt

import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ActiveHuntTest {
    private val region = RegionKey(34, -85)
    private val local = LocalList(
        listOf(Eligible(0, "water oak", 72), Eligible(1, "sweetgum", 50), Eligible(2, "redbud", 40)),
        intArrayOf(3),
        needsWiden = false,
    )

    @Test
    fun `a fresh hunt keeps the local rows its verify needs`() {
        val hunt = ActiveHunt.start(Hunt(tutorial = true, local.eligible.take(2)), local, region)

        assertTrue(hunt.progress.tutorialPending)
        assertEquals(listOf(0, 1, 2), hunt.eligible)
        assertEquals(listOf(3), hunt.blockers)
        assertEquals(setOf(0, 1, 2, 3), hunt.local)
    }

    @Test
    fun `the goal scores the target against the hunt's own rows`() {
        val table = FloatMatrix(4, 2, floatArrayOf(1f, 0f, 0f, 1f, 0.6f, 0.8f, -1f, 0f))
        val hunt = ActiveHunt.start(Hunt(false, local.eligible), local, region)
        val genus = listOf("Quercus", "Liquidambar", "Cercis", "Toxicodendron")

        assertTrue(hunt.goal(table, genus, 0).score(floatArrayOf(1f, 0f)).met)
        assertFalse(hunt.goal(table, genus, 1).score(floatArrayOf(1f, 0f)).met)
    }

    @Test
    fun `targets must be eligible and blockers never are`() {
        val progress = HuntProgress.start(Hunt(false, local.eligible))

        assertThrows<IllegalArgumentException> { ActiveHunt(progress, region, listOf(0, 1), listOf(3)) }
        assertThrows<IllegalArgumentException> { ActiveHunt(progress, region, listOf(0, 1, 2, 3), listOf(3)) }
    }

    @Test
    fun `restoration requires the selected region and the complete playable plan`() {
        val table = listOf("Quercus", "Liquidambar", "Cercis", "Toxicodendron").mapIndexed { i, genus ->
            SpeciesRow("$genus species", genus, hazard = i == 3, toxic = i == 3)
        }
        val hunt = ActiveHunt.start(Hunt(false, local.eligible), local, region)

        assertTrue(hunt.validFor(region, table))
        assertFalse(hunt.validFor(null, table))
        assertFalse(hunt.validFor(RegionKey(34, -84), table))
        assertFalse(hunt.validFor(region, emptyList()))
        assertFalse(hunt.copy(eligible = hunt.eligible + 0).validFor(region, table))
        assertFalse(hunt.copy(blockers = listOf(3, 3)).validFor(region, table))
        assertFalse(hunt.copy(blockers = listOf(-1)).validFor(region, table))
        assertFalse(hunt.copy(blockers = listOf(4)).validFor(region, table))
        assertFalse(
            hunt.copy(blockers = listOf(3)).validFor(
                region,
                table.map {
                    it.copy(hazard = false, toxic = false)
                },
            ),
        )
        assertFalse(hunt.validFor(region, table.map { it.copy(toxic = true) }))
        assertFalse(hunt.validFor(region, table.map { it.copy(genus = "Shared") }))
        assertFalse(hunt.copy(progress = hunt.progress.copy(targets = local.eligible.take(2))).validFor(region, table))
        assertFalse(hunt.copy(progress = hunt.progress.copy(queue = listOf(local.eligible[0]))).validFor(region, table))
        val fewer = hunt.copy(
            progress = hunt.progress.copy(targets = local.eligible.take(2), queue = listOf(local.eligible[2])),
        )
        assertFalse(fewer.validFor(region, table))
    }

    @Test
    fun `a restored skip queue is unique and contains only its eligible rows`() {
        val table = listOf("Quercus", "Liquidambar", "Cercis", "Acer").map { genus ->
            SpeciesRow("$genus species", genus, hazard = false, toxic = false)
        }
        val queued = Eligible(3, "maple", 10)
        val progress = HuntProgress(false, local.eligible, queue = listOf(queued))
        val hunt = ActiveHunt(progress, region, listOf(0, 1, 2, 3), emptyList())

        assertTrue(hunt.validFor(region, table))
        assertTrue(hunt.copy(progress = progress.skip(0) { table[it].genus }).validFor(region, table))
        assertFalse(hunt.copy(progress = progress.copy(queue = listOf(queued, queued))).validFor(region, table))
        assertFalse(hunt.copy(progress = progress.copy(queue = listOf(queued.copy(row = -1)))).validFor(region, table))
        assertFalse(hunt.copy(progress = progress.copy(queue = emptyList())).validFor(region, table))
    }
}
