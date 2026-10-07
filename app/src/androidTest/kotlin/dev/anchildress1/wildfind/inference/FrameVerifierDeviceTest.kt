package dev.anchildress1.wildfind.inference

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.frame.Bicubic
import dev.anchildress1.wildfind.core.frame.Box
import dev.anchildress1.wildfind.core.frame.Crops
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
import java.util.concurrent.ForkJoinPool

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
                Log.i(TAG, "hazard ranks ${result.reticleRanking?.hazardRank} ${result.fullRanking?.hazardRank}")

                assertEquals(5, labels.words.size)
                // A 224-square fixture: the full-frame crop resizes to itself, so the laptop share must hold.
                assertEquals(gateReference().getDouble("plant_share"), result.fullShare, SHARE_TOLERANCE)
                assertTrue(result.evidence.reticlePlant)
                assertFalse(result.evidence.hazard)
                assertTrue(result.fullRanking!!.hazardRank > HazardCheck.TOP_K)
            }
        }
    }

    @Test
    fun cropAndResizeAtTheDefaultAnalysisSizeOnATallScreen() {
        // The S24 Ultra's live geometry from the gate harness: a 1920 x 1440 buffer, the 1920 x 886 strip a
        // 1080 x 2340 viewport shows, rotated 90 degrees to upright 886 x 1920.
        val buffer = ByteBuffer.allocateDirect(Crops.ANALYSIS_WIDTH * Crops.ANALYSIS_HEIGHT * BYTES_PER_PIXEL)
        repeat(buffer.capacity()) { buffer.put(it, (it * PIXEL_NOISE).toByte()) }
        val frame =
            RgbaFrame(buffer, Crops.ANALYSIS_WIDTH * BYTES_PER_PIXEL, Box(0, VISIBLE_TOP, 1920, VISIBLE_HEIGHT), 90)

        fun once(): Pair<Long, Long> {
            val start = System.nanoTime()
            val reticle = frame.upright(Crops.reticle(frame.width, frame.height))
            val full = frame.upright(Crops.fullFrame(frame.width, frame.height))
            val cropped = System.nanoTime()
            Bicubic.resize(reticle, Crops.MODEL_SIZE, Crops.MODEL_SIZE)
            Bicubic.resize(full, Crops.MODEL_SIZE, Crops.MODEL_SIZE)
            return (cropped - start) / NANOS_PER_MS to (System.nanoTime() - cropped) / NANOS_PER_MS
        }
        repeat(WARM_UP) { once() }
        val runs = List(RUNS * 4) { once() }
        val cores = "${Runtime.getRuntime().availableProcessors()} cores, pool ${ForkJoinPool.commonPool().parallelism}"
        Log.i(TAG, "upright ${frame.width}x${frame.height}, $cores; crop ms ${runs.map { it.first }}")
        Log.i(TAG, "resize ms ${runs.map { it.second }}")

        assertEquals(886 to 1920, frame.width to frame.height)
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
        const val VISIBLE_TOP = 277
        const val VISIBLE_HEIGHT = 886
        const val PIXEL_NOISE = 31
        const val WARM_UP = 10
        const val NANOS_PER_MS = 1_000_000L
    }
}
