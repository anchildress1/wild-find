package dev.anchildress1.wildfind.inference

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/** Day-1 gate: the on-device encoder must reproduce the laptop reference from `make reference`. */
@RunWith(AndroidJUnit4::class)
class BioclipParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reference = JSONObject(
        instrumentation.context.assets.open("reference/reference.json").bufferedReader().use { it.readText() },
    )
    private val modelFile = File(
        instrumentation.targetContext.noBackupFilesDir,
        "models/" + reference.getJSONObject("image_model").getString("file"),
    )

    @Test
    fun fixtureEmbeddingMatchesTheLaptopReferenceAndPicksTheFixtureWord() {
        assertTrue("model missing; run make push-models", modelFile.isFile)
        val bitmap = instrumentation.context.assets.open("reference/fixture.png").use(BitmapFactory::decodeStream)

        val loadStart = System.nanoTime()
        val encoder = BioclipImageEncoder(modelFile)
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        val embedding = encoder.use {
            it.embed(bitmap) // warm-up run, excluded from timing
            val start = System.nanoTime()
            it.embed(bitmap).also {
                Log.i(TAG, "load ${"%.0f".format(loadMs)} ms, embed ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        }

        val expected = floats(reference.getJSONArray("image_embedding"))
        val cosine = dot(embedding, expected) / (norm(embedding) * norm(expected))
        Log.i(TAG, "cosine to laptop reference: $cosine")
        assertTrue("cosine $cosine below $MIN_COSINE", cosine >= MIN_COSINE)

        val labels = reference.getJSONArray("labels")
        val scores = (0 until labels.length()).associate { i ->
            labels.getJSONObject(i).let { it.getString("id") to dot(embedding, floats(it.getJSONArray("vector"))) }
        }
        Log.i(
            TAG,
            "scores: ${scores.entries.sortedByDescending {
                it.value
            }.joinToString { "${it.key}=%.4f".format(it.value) }}",
        )
        assertEquals(reference.getJSONObject("fixture").getString("word"), scores.maxBy { it.value }.key)
    }

    private fun floats(array: JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }

    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    private fun norm(a: FloatArray) = sqrt(dot(a, a))

    private companion object {
        const val TAG = "BioclipParity"

        // Phone (ARM NEON) and laptop ONNX Runtime CPU kernels round fp32 math differently, so allow tiny drift.
        // 0.999 still catches any preprocessing mistake (channel order, scaling, normalization),
        // which drops cosine far lower.
        const val MIN_COSINE = 0.999
    }
}
