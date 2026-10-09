package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LabelSetTest {
    private val tutorial = listOf(
        "Quercus", "Poaceae", "Polypodiopsida", "Trifolium", "Pinus", "Taraxacum",
        "Toxicodendron radicans", "Toxicodendron pubescens", "Toxicodendron vernix", "Phytolacca americana",
        "Solanum carolinense",
    )

    /** Grass alone along x, so it ranks first for an embedding along x. */
    private fun set(names: List<String>) = LabelSet(
        names,
        FloatMatrix(names.size, 2, FloatArray(names.size * 2) { if (it == 2 * names.indexOf("Poaceae")) 1f else 0f }),
    )

    @Test
    fun `the tutorial goal scores grass among every tutorial label`() {
        assertEquals(GoalScore(true, 1.0, 1), set(tutorial).tutorialGoal().score(floatArrayOf(1f, 0f)))
    }

    @Test
    fun `the label set must match its vectors and be exactly the tutorial set`() {
        assertThrows<IllegalArgumentException> { LabelSet(tutorial, FloatMatrix(1, 2, FloatArray(2))) }
        assertThrows<IllegalArgumentException> { set(tutorial.drop(1)) }
        assertThrows<IllegalArgumentException> { set(tutorial + "Acer") }
        assertThrows<IllegalArgumentException> { set(tutorial.drop(1) + "Poaceae") }
        assertThrows<IllegalArgumentException> { set(tutorial - "Poaceae" + "Acer") }
    }
}
