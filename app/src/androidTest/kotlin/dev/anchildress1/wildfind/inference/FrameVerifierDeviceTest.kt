package dev.anchildress1.wildfind.inference

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.frame.Box
import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.frame.RgbaFrame
import dev.anchildress1.wildfind.core.frame.blue
import dev.anchildress1.wildfind.core.frame.green
import dev.anchildress1.wildfind.core.frame.red
import dev.anchildress1.wildfind.core.verify.Focus
import dev.anchildress1.wildfind.core.verify.FrameVerifier
import dev.anchildress1.wildfind.core.verify.HazardCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** The whole per-frame verify path on the phone: camera buffer, crops, both bundled models, species table, labels. */
@RunWith(AndroidJUnit4::class)
class FrameVerifierDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val bundled = BundledAssets(instrumentation.targetContext.assets)

    @Test
    fun theFixtureFramePassesTheGateAndNoHazardWithTheLaptopPlantShare() {
        val labels = bundled.labels()
        val goal = labels.targetGoal("oak", labels.words, floor = null, margin = null)
        val frame = rgbaFrame(testBitmap("reference/fixture.png").pixels())
        val focus = Focus(Focus.AF_FOCUSED_LOCKED, CLOSE_DIOPTERS, 1f)

        ImageEncoder(bundled.plantGateModel()).use { gate ->
            ImageEncoder(bundled.bioclipModel()).use { bioclip ->
                val verifier = FrameVerifier(bundled.plantGate(), gate, bioclip, bundled.hazardCheck())
                verifier.analyze(frame, goal) { focus } // warm-up, excluded from timing
                val runs = List(RUNS) { verifier.analyze(frame, goal) { focus } }
                runs.forEach { Log.i(TAG, "times ns ${it.times}") }
                val result = runs.last()
                Log.i(TAG, "shares ${result.reticleShare} ${result.fullShare}, goal ${result.goal}")
                Log.i(TAG, "hazard ranks ${result.reticleHazardRank} ${result.fullHazardRank}")

                assertEquals(5, labels.words.size)
                // A 224-square fixture: the full-frame crop resizes to itself, so the laptop share must hold.
                assertEquals(gateReference().getDouble("plant_share"), result.fullShare, SHARE_TOLERANCE)
                assertTrue(result.evidence.reticlePlant)
                assertFalse(result.evidence.hazard)
                assertTrue(result.fullHazardRank!! > HazardCheck.TOP_K)
            }
        }
    }

    private fun gateReference(): JSONObject {
        val json = instrumentation.context.assets.open("reference/plant_gate_reference.json")
        return JSONObject(json.bufferedReader().use { it.readText() })
    }

    private fun rgbaFrame(pixels: Pixels): RgbaFrame {
        val buffer = ByteBuffer.allocateDirect(pixels.argb.size * BYTES_PER_PIXEL)
        pixels.argb.forEach {
            buffer.put(red(it).toByte()).put(green(it).toByte()).put(blue(it).toByte()).put(OPAQUE)
        }
        return RgbaFrame(buffer, pixels.width * BYTES_PER_PIXEL, Box(0, 0, pixels.width, pixels.height), 0)
    }

    private companion object {
        const val TAG = "FrameVerifierDevice"
        const val RUNS = 5
        const val BYTES_PER_PIXEL = 4
        const val OPAQUE: Byte = -1
        const val CLOSE_DIOPTERS = 5f
        const val SHARE_TOLERANCE = 1e-3
    }
}
