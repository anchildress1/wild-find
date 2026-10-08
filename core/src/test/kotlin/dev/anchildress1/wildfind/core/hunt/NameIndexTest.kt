package dev.anchildress1.wildfind.core.hunt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NameIndexTest {
    private val rows = listOf(
        SpeciesRow("Quercus nigra", "Quercus", hazard = false, toxic = false, type = PlantType.TREE),
        SpeciesRow("Toxicodendron radicans", "Toxicodendron", hazard = true, toxic = true),
    )

    @Test
    fun `an exact scientific name finds its row, anything else none`() {
        val index = NameIndex(rows)

        assertEquals(1, index.rowOf("Toxicodendron radicans"))
        assertNull(index.rowOf("quercus nigra"))
        assertNull(index.rowOf("Quercus"))
    }

    @Test
    fun `plant types read the pipeline's names, null for none, and reject unknown ones`() {
        assertEquals(PlantType.CONIFER, PlantType.of("conifer"))
        assertNull(PlantType.of(null))
        assertThrows<IllegalArgumentException> { PlantType.of("cactus") }
        assertEquals(PlantType.entries.size, PlantType.entries.map { it.key }.toSet().size)
    }
}
