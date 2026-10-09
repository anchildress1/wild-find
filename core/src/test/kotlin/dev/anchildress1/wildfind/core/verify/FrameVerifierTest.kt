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
    // 30 x 10: the reticle square is green, everything else red, so each fake encoder can tell which crop it got.
    private val reticleBox = Crops.reticle(WIDTH, HEIGHT)
    private val frame = cameraFrame(
        Pixels(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { if (inReticle(it % WIDTH, it / WIDTH)) GREEN else RED }),
        rotationDegrees = 0,
    )

    // Gate: plant along x, not-plant along y.
    private val gate = PlantGate(
        listOf(PlantGate.Label(true, floatArrayOf(1f, 0f)), PlantGate.Label(false, floatArrayOf(0f, 1f))),
        50f,
    )

    // Species table: six safe species along x, one hazard along y.
    // Target table: the target along x, a rival genus along y.
    private val hazards = HazardCheck(
        FloatMatrix(7, 2, FloatArray(14) { if (it < 12) (1 - it % 2).toFloat() else (it - 12).toFloat() }),
        BooleanArray(7) { it == 6 },
        BooleanArray(7) { true },
    )
    private val labels = FloatMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))
    private val target = TargetGoal(labels, listOf("Quercus", "Acer"), 0, intArrayOf(0, 1), intArrayOf())
    private val close = Focus(Focus.AF_FOCUSED_LOCKED, 5f, 1f)

    private val plant = floatArrayOf(1f, 0f)
    private val notPlant = floatArrayOf(0f, 1f)
    private val safe = floatArrayOf(1f, 0f)
    private val hazard = floatArrayOf(0f, 1f)

    private val calls = mutableListOf<String>()

    /** A verifier whose fakes answer per crop: [gateOut] and [bioclipOut] get true for the reticle crop. */
    private fun verifier(gateOut: (Boolean) -> FloatArray, bioclipOut: (Boolean) -> FloatArray) = FrameVerifier(
        gate,
        { p -> gateOut(p.isReticle()).also { calls += "gate:${region(p)}" } },
        { p -> bioclipOut(p.isReticle()).also { calls += "bioclip:${region(p)}" } },
        hazards,
        clock = Ticks(),
    )

    @Test
    fun `plants in both regions run BioCLIP on each and score the goal on the reticle`() {
        val result = verifier({ plant }, { safe }).analyze(frame, target) { close }

        assertEquals(listOf("gate:reticle", "gate:full", "bioclip:reticle", "bioclip:full"), calls)
        assertEquals(FrameEvidence(hazard = false, reticlePlant = true, focus = close, goalMet = true), result.evidence)
        assertEquals(7, result.reticleRanking?.hazardRank)
        assertEquals(7, result.fullRanking?.hazardRank)
        assertEquals(1, result.goal?.rank)
    }

    @Test
    fun `a hazard filling the reticle warns even when the full frame isn't a plant`() {
        val result = verifier({ if (it) plant else notPlant }, { hazard }).analyze(frame, target) { close }

        assertEquals(listOf("gate:reticle", "gate:full", "bioclip:reticle"), calls)
        assertTrue(result.evidence.hazard)
        assertEquals(1, result.reticleRanking?.hazardRank)
        assertNull(result.fullRanking)
        assertTrue(PlantGate.isPlant(result.reticleShare))
        assertFalse(PlantGate.isPlant(result.fullShare))
    }

    @Test
    fun `a hazard in the full frame warns even when the reticle isn't a plant`() {
        val result = verifier({ if (it) notPlant else plant }, { hazard }).analyze(frame, target) { close }

        assertEquals(listOf("gate:reticle", "gate:full", "bioclip:full"), calls)
        assertTrue(result.evidence.hazard)
        assertFalse(result.evidence.reticlePlant)
        assertNull(result.reticleRanking)
        assertEquals(1, result.fullRanking?.hazardRank)
        assertNull(result.goal)
    }

    @Test
    fun `a hazard in either plant region warns while the other looks safe`() {
        val result = verifier({ plant }, { if (it) safe else hazard }).analyze(frame, target) { close }

        assertTrue(result.evidence.hazard)
        assertEquals(7, result.reticleRanking?.hazardRank)
        assertEquals(1, result.fullRanking?.hazardRank)
    }

    @Test
    fun `no plant anywhere skips BioCLIP`() {
        val result = verifier({ notPlant }, { error("BioCLIP must not run") }).analyze(frame, target) { null }

        assertEquals(listOf("gate:reticle", "gate:full"), calls)
        assertEquals(
            FrameEvidence(hazard = false, reticlePlant = false, focus = null, goalMet = false),
            result.evidence,
        )
    }

    @Test
    fun `the tutorial skips the hazard check and the full-frame BioCLIP`() {
        val tutorial = TutorialGoal(labels, 0, intArrayOf(0, 1))
        val result = verifier({ plant }, { hazard }).analyze(frame, tutorial) { close }

        assertEquals(listOf("gate:reticle", "gate:full", "bioclip:reticle"), calls)
        assertFalse(result.evidence.hazard)
        assertNull(result.reticleRanking)
        assertNull(result.fullRanking)
        assertTrue(result.evidence.goalMet)
    }

    @Test
    fun `each stage is timed and the stages sum to the total`() {
        val times = verifier({ plant }, { safe }).analyze(frame, target) { close }.times

        assertEquals(
            StageTimes(crop = 1, resize = 1, plantGate = 1, bioclip = 1, hazard = 1, goal = 1, total = 7),
            times,
        )
    }

    @Test
    fun `focus is asked for after the models ran`() {
        verifier({ plant }, { safe }).analyze(frame, target) {
            calls += "focus"
            close
        }

        assertEquals("focus", calls.last())
    }

    @Test
    fun `a non-finite embedding fails loudly and names the encoder and crop`() {
        val error = assertThrows<IllegalStateException> {
            verifier({ plant }, { floatArrayOf(Float.NaN, 0f) }).analyze(frame, target) { close }
        }

        assertEquals("BioCLIP reticle embedding is not finite", error.message)
    }

    @Test
    fun `the capture is the reticle square at analysis resolution`() {
        val result = verifier({ notPlant }, { safe }).analyze(frame, target) { null }

        assertEquals(Box(12, 2, 6, 6), reticleBox)
        assertEquals(6 to 6, result.reticle.width to result.reticle.height)
        assertTrue(result.reticle.isReticle())
    }

    private fun inReticle(x: Int, y: Int) = x in reticleBox.left until reticleBox.left + reticleBox.width &&
        y in reticleBox.top until reticleBox.top + reticleBox.height

    private fun Pixels.isReticle() = argb.all { it == GREEN }

    private fun region(p: Pixels) = if (p.isReticle()) "reticle" else "full"

    /** A clock that advances one nanosecond per read. */
    private class Ticks : () -> Long {
        private var now = 0L

        override fun invoke() = now++
    }

    private companion object {
        const val WIDTH = 30
        const val HEIGHT = 10
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
