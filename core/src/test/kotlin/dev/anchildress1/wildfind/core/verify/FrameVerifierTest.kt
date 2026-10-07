package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.frame.Box
import dev.anchildress1.wildfind.core.frame.Crops
import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.frame.cameraFrame
import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameVerifierTest {
    // The frame's left half is red, right half is blue; the reticle crop sees only the middle.
    private val frame = cameraFrame(
        Pixels(20, 10, IntArray(200) { if (it % 20 < 10) RED else BLUE }),
        rotationDegrees = 0,
    )

    // Gate: plant along x, not-plant along y. BioCLIP: six safe species along x, one hazard along y, target along x.
    private val gate = PlantGate(
        listOf(PlantGate.Label(true, floatArrayOf(1f, 0f)), PlantGate.Label(false, floatArrayOf(0f, 1f))),
        50f,
    )
    private val hazards = HazardCheck(
        FloatMatrix(7, 2, FloatArray(14) { if (it < 12) (1 - it % 2).toFloat() else (it - 12).toFloat() }),
        BooleanArray(7) { it == 6 },
    )
    private val labels = FloatMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))
    private val target = TargetGoal(labels, 0, intArrayOf(0, 1), null, null)
    private val close = Focus(Focus.AF_FOCUSED_LOCKED, 5f, 1f)

    private val plant = floatArrayOf(1f, 0f)
    private val notPlant = floatArrayOf(0f, 1f)

    private fun verifier(
        gateOut: (Pixels) -> FloatArray,
        bioclipOut: (Pixels) -> FloatArray,
        calls: MutableList<String>,
    ) = FrameVerifier(
        gate,
        { p ->
            calls += "gate:${p.width}"
            gateOut(p)
        },
        { p ->
            calls += "bioclip"
            bioclipOut(p)
        },
        hazards,
        clock = Ticks(),
    )

    @Test
    fun `a plant in the reticle runs both gates, BioCLIP on each plant region, and scores the goal`() {
        val calls = mutableListOf<String>()
        val result = verifier({ plant }, { plant }, calls).analyze(frame, target) { close }

        assertEquals(listOf("gate:224", "gate:224", "bioclip", "bioclip"), calls)
        assertEquals(FrameEvidence(hazard = false, reticlePlant = true, focus = close, goalMet = true), result.evidence)
        assertEquals(7, result.reticleHazardRank)
        assertEquals(7, result.fullHazardRank)
        assertEquals(1, result.goal?.rank)
        assertEquals(Crops.reticle(20, 10).width, result.reticle.width)
    }

    @Test
    fun `the full frame alone can raise the hazard`() {
        val calls = mutableListOf<String>()
        // Gate: reticle crop (first call) is not a plant, full frame is. BioCLIP on the full frame hits the hazard.
        var gateCalls = 0
        val result = verifier({ if (gateCalls++ == 0) notPlant else plant }, { notPlant }, calls)
            .analyze(frame, target) { close }

        assertEquals(listOf("gate:224", "gate:224", "bioclip"), calls)
        assertTrue(result.evidence.hazard)
        assertFalse(result.evidence.reticlePlant)
        assertNull(result.reticleHazardRank)
        assertEquals(1, result.fullHazardRank)
        assertNull(result.goal)
    }

    @Test
    fun `no plant anywhere skips BioCLIP`() {
        val calls = mutableListOf<String>()
        val result = verifier({ notPlant }, { error("BioCLIP must not run") }, calls).analyze(frame, target) { null }

        assertEquals(
            FrameEvidence(hazard = false, reticlePlant = false, focus = null, goalMet = false),
            result.evidence,
        )
        assertTrue(result.reticleShare < PlantGate.THRESHOLD && result.fullShare < PlantGate.THRESHOLD)
    }

    @Test
    fun `the tutorial skips the hazard check and the full-frame BioCLIP`() {
        val calls = mutableListOf<String>()
        val tutorial = TutorialGoal(labels, 0, intArrayOf(0, 1))
        val result = verifier({ plant }, { notPlant }, calls).analyze(frame, tutorial) { close }

        assertEquals(listOf("gate:224", "gate:224", "bioclip"), calls)
        assertFalse(result.evidence.hazard)
        assertNull(result.reticleHazardRank)
        assertNull(result.fullHazardRank)
        assertTrue(result.evidence.goalMet)
    }

    @Test
    fun `each stage is timed and the stages sum to the total`() {
        val times = verifier({ plant }, { plant }, mutableListOf()).analyze(frame, target) { close }.times

        assertEquals(
            StageTimes(crop = 1, resize = 1, plantGate = 1, bioclip = 1, hazard = 1, goal = 1, total = 7),
            times,
        )
    }

    @Test
    fun `focus is asked for after the models ran`() {
        val calls = mutableListOf<String>()
        verifier({ plant }, { plant }, calls).analyze(frame, target) {
            calls += "focus"
            close
        }

        assertEquals("focus", calls.last())
    }

    @Test
    fun `a non-finite embedding fails loudly instead of ranking every hazard first`() {
        assertThrows<IllegalStateException> {
            verifier({ plant }, { floatArrayOf(Float.NaN, 0f) }, mutableListOf()).analyze(frame, target) { close }
        }
    }

    @Test
    fun `the reticle crop is the frame's middle`() {
        val result = verifier({ notPlant }, { plant }, mutableListOf()).analyze(frame, target) { null }

        assertEquals(Box(7, 2, 6, 6), Crops.reticle(20, 10))
        assertEquals(setOf(RED, BLUE), result.reticle.argb.toSet())
    }

    /** A clock that advances one nanosecond per read. */
    private class Ticks : () -> Long {
        private var now = 0L

        override fun invoke() = now++
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
