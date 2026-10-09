package dev.anchildress1.wildfind.core.hunt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HuntPickTest {
    private val table = listOf("Quercus", "Quercus", "Acer", "Liquidambar", "Magnolia", "Fagus")
        .mapIndexed { row, genus -> SpeciesRow("$genus s$row", genus, hazard = false, toxic = false) }

    private fun local(vararg counts: Int) = LocalList(
        counts.mapIndexed { row, count ->
            Eligible(row, "name $row", count)
        },
        intArrayOf(),
        needsWiden = false,
    )

    @Test
    fun `a hunt takes three targets, never two from one genus`() {
        repeat(200) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(50, 50, 10, 10, 10, 10), tutorialDone = true)

            assertEquals(3, hunt.targets.size)
            assertEquals(3, hunt.targets.map { table[it.row].genus }.distinct().size)
        }
    }

    @Test
    fun `targets are drawn by sightings`() {
        val counts = IntArray(table.size)
        repeat(4000) { seed ->
            HuntPick(table, Random(seed)).next(local(0, 0, 900, 50, 25, 25), tutorialDone = true).targets
                .first().let { counts[it.row]++ }
        }

        // Expected first-pick shares: 90%, 5%, 2.5%, 2.5%.
        assertTrue(counts[2] in 3400..3800, counts.toList().toString())
        assertTrue(counts[3] in 100..300, counts.toList().toString())
        assertEquals(0, counts[0] + counts[1])
    }

    @Test
    fun `too few genera leave a short hunt for the coverage message`() {
        val hunt = HuntPick(table, Random(1)).next(local(10, 10, 10), tutorialDone = true)

        assertEquals(setOf("Quercus", "Acer"), hunt.targets.map { table[it.row].genus }.toSet())
    }

    @Test
    fun `an empty list makes an empty hunt`() {
        assertTrue(HuntPick(table, Random(1)).next(local(), tutorialDone = true).targets.isEmpty())
    }

    @Test
    fun `only the first-ever hunt opens with the grass tutorial`() {
        assertTrue(HuntPick(table, Random(1)).next(local(5, 5, 5, 5), tutorialDone = false).tutorial)
        assertFalse(HuntPick(table, Random(1)).next(local(5, 5, 5, 5), tutorialDone = true).tutorial)
    }

    @Test
    fun `every eligible species not picked waits in the queue`() {
        val hunt = HuntPick(table, Random(3)).next(local(10, 10, 10, 10, 10, 10), tutorialDone = true)

        assertEquals((0..5).toSet(), (hunt.targets + hunt.queue).map { it.row }.toSet())
        assertEquals(6, hunt.targets.size + hunt.queue.size)
    }

    private fun hintedTable(vararg hinted: Int) = table.mapIndexed { row, species ->
        species.copy(hints = if (row in hinted) listOf(Hint("Look near water.")) else emptyList())
    }

    @Test
    fun `hinted species fill the hunt first, by sightings, one per genus`() {
        val table = hintedTable(1, 3, 4, 5)
        repeat(200) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(900, 1, 900, 1, 1, 1), tutorialDone = true)

            assertTrue(hunt.targets.all { table[it.row].hints.isNotEmpty() }, hunt.targets.toString())
            assertEquals(3, hunt.targets.map { table[it.row].genus }.distinct().size)
        }
    }

    @Test
    fun `too few hinted species leave the rest of the hunt to the unhinted`() {
        val table = hintedTable(4)
        repeat(50) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(10, 10, 10, 10, 10, 10), tutorialDone = true)

            assertEquals(4, hunt.targets.first().row)
            assertEquals(3, hunt.targets.size)
            assertEquals(3, hunt.targets.map { table[it.row].genus }.distinct().size)
        }
    }

    @Test
    fun `the skip queue lists hinted species before unhinted ones`() {
        val table = hintedTable(0, 2, 3)
        repeat(50) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(10, 10, 10, 10, 10, 10), tutorialDone = true)
            val flags = hunt.queue.map { table[it.row].hints.isNotEmpty() }

            assertEquals(flags.sortedDescending(), flags, hunt.queue.toString())
            assertEquals(6, hunt.targets.size + hunt.queue.size)
        }
    }

    private fun names(vararg rows: Int) = rows.map { table[it].scientific }.toSet()

    @Test
    fun `plants found before stay out of the hunt while others can fill it`() {
        repeat(100) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(9, 9, 9, 9, 9, 9), true, found = names(2, 3))

            assertEquals(3, hunt.targets.size)
            assertTrue(hunt.targets.none { it.row == 2 || it.row == 3 }, hunt.targets.toString())
        }
    }

    @Test
    fun `found plants return only to fill a hunt the others cannot`() {
        repeat(50) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(9, 9, 9, 9, 9, 9), true, found = names(2, 3, 4, 5))

            assertEquals(3, hunt.targets.size)
            assertTrue(hunt.targets.any { it.row == 0 || it.row == 1 }, "the one unfound genus is always in")
            assertEquals(3, hunt.targets.map { table[it.row].genus }.distinct().size)
        }
    }

    @Test
    fun `the skip queue lists unfound plants before found ones`() {
        repeat(50) { seed ->
            val hunt = HuntPick(table, Random(seed)).next(local(9, 9, 9, 9, 9, 9), true, found = names(0, 1, 2))
            val seen = hunt.queue.map { it.row in setOf(0, 1, 2) }

            assertEquals(seen.sorted(), seen, hunt.queue.toString())
        }
    }
}
