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
}
