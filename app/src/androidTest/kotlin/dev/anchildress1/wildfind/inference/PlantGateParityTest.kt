package dev.anchildress1.wildfind.inference

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.verify.PlantGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/** S06: the bundled TinyCLIP plant gate must reproduce the laptop export from `make assets`. */
@RunWith(AndroidJUnit4::class)
class PlantGateParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reference = JSONObject(
        instrumentation.context.assets.open("reference/plant_gate_reference.json").bufferedReader().use {
            it.readText()
        },
    )
    private val bundled = BundledAssets(instrumentation.targetContext.assets)

    @Test
    fun fixtureEmbeddingAndPlantShareMatchTheLaptop() {
        val fixture = testBitmap("reference/fixture.png").pixels()
        val gate = bundled.plantGate()

        val loadStart = System.nanoTime()
        val encoder = ImageEncoder(bundled.plantGateModel())
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        val embedding = encoder.use {
            it.embed(fixture) // warm-up run, excluded from timing
            val start = System.nanoTime()
            it.embed(fixture).also {
                Log.i(TAG, "load ${"%.0f".format(loadMs)} ms, embed ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        }

        val expected = reference.getJSONArray("image_embedding").let { a ->
            FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }
        val cosine = dot(embedding, expected) / (sqrt(dot(embedding, embedding)) * sqrt(dot(expected, expected)))
        val share = gate.plantShare(embedding)
        Log.i(TAG, "cosine to laptop reference: $cosine, plant share $share")
        assertTrue("cosine $cosine below $MIN_COSINE", cosine >= MIN_COSINE)
        assertEquals(reference.getDouble("plant_share"), share, SHARE_TOLERANCE)
        assertTrue("the oak fixture must pass the gate", PlantGate.isPlant(share))
    }

    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    private companion object {
        const val TAG = "PlantGateParity"

        // Same fp32 drift allowance as BioclipParityTest; a preprocessing mistake drops cosine far lower.
        const val MIN_COSINE = 0.999
        const val SHARE_TOLERANCE = 1e-3
    }
}
