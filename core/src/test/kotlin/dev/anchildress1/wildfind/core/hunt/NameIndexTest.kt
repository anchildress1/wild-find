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
    fun `a synonym finds its row, and a name on two rows fails the build`() {
        val withAlias =
            rows + SpeciesRow("Hexastylis arifolia", "Hexastylis", false, true, synonyms = listOf("Asarum arifolium"))

        assertEquals(2, NameIndex(withAlias).rowOf("Asarum arifolium"))
        assertEquals(2, NameIndex(withAlias).rowOf("Hexastylis arifolia"))
        assertThrows<IllegalArgumentException> {
            NameIndex(
                withAlias +
                    SpeciesRow("Asarum canadense", "Asarum", false, false, synonyms = listOf("Asarum arifolium")),
            )
        }
    }

    @Test
    fun `plant types read the pipeline's names, null for none, and reject unknown ones`() {
        assertEquals(PlantType.CONIFER, PlantType.of("conifer"))
        assertNull(PlantType.of(null))
        assertThrows<IllegalArgumentException> { PlantType.of("cactus") }
        assertEquals(PlantType.entries.size, PlantType.entries.map { it.key }.toSet().size)
    }
}
