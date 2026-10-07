package dev.anchildress1.wildfind.core.verify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.exp

class PlantGateTest {
    private val gate = PlantGate(
        listOf(
            PlantGate.Label(isPlant = true, vector = floatArrayOf(1f, 0f)),
            PlantGate.Label(isPlant = true, vector = floatArrayOf(0.6f, 0.8f)),
            PlantGate.Label(isPlant = false, vector = floatArrayOf(0f, 1f)),
        ),
        logitScale = 50f,
    )

    @Test
    fun `plant share sums the softmax of every plant label`() {
        val embedding = floatArrayOf(0f, 1f)
        val logits = listOf(0.0, 50.0 * 0.8, 50.0)
        val weights = logits.map { exp(it - 50.0) }

        assertEquals((weights[0] + weights[1]) / weights.sum(), gate.plantShare(embedding), 1e-6)
    }

    @Test
    fun `scale changes the verdict, so it is never optional`() {
        // Two weak plant matches outweigh one stronger non-plant match only when the scale is left out.
        val labels = listOf(
            PlantGate.Label(isPlant = true, vector = floatArrayOf(0.5f, 0.866f)),
            PlantGate.Label(isPlant = true, vector = floatArrayOf(0.5f, -0.866f)),
            PlantGate.Label(isPlant = false, vector = floatArrayOf(0.6f, 0.8f)),
        )
        val embedding = floatArrayOf(1f, 0f)

        assertFalse(PlantGate(labels, logitScale = 50f).isPlant(embedding))
        assertTrue(PlantGate(labels, logitScale = 1f).isPlant(embedding))
    }

    @Test
    fun `a plant-facing embedding passes`() {
        assertTrue(gate.isPlant(floatArrayOf(1f, 0f)))
    }

    @Test
    fun `a share of exactly the threshold is not a plant`() {
        val even = PlantGate(
            listOf(PlantGate.Label(true, floatArrayOf(1f)), PlantGate.Label(false, floatArrayOf(1f))),
            logitScale = 50f,
        )

        assertEquals(PlantGate.THRESHOLD, even.plantShare(floatArrayOf(1f)), 1e-12)
        assertFalse(even.isPlant(floatArrayOf(1f)))
    }

    @Test
    fun `needs both plant and not-plant labels`() {
        assertThrows<IllegalArgumentException> { PlantGate(listOf(PlantGate.Label(true, floatArrayOf(1f))), 50f) }
    }

    @Test
    fun `rejects labels of different sizes`() {
        assertThrows<IllegalArgumentException> {
            PlantGate(
                listOf(PlantGate.Label(true, floatArrayOf(1f)), PlantGate.Label(false, floatArrayOf(1f, 0f))),
                50f,
            )
        }
    }

    @Test
    fun `rejects an embedding of the wrong size`() {
        assertThrows<IllegalArgumentException> { gate.plantShare(floatArrayOf(1f)) }
    }
}
