package dev.anchildress1.wildfind.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * ONNX image encoder for BioCLIP 2.5 Mobile and the TinyCLIP plant gate: a 224x224 RGB bitmap in, a unit embedding out.
 *
 * Both graphs take an `image` input of plain 0..1 RGB and apply their own normalization.
 */
class ImageEncoder(model: ByteBuffer) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions()
    private val session = env.createSession(model, options)

    /** Embeds [bitmap], which must be exactly [SIZE]x[SIZE]; resizing and cropping are the caller's job. */
    fun embed(bitmap: Bitmap): FloatArray {
        require(bitmap.width == SIZE && bitmap.height == SIZE) {
            "expected ${SIZE}x$SIZE, got ${bitmap.width}x${bitmap.height}"
        }
        OnnxTensor.createTensor(env, chwInput(bitmap), SHAPE).use { input ->
            session.run(mapOf(INPUT to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<FloatArray>)[0]
            }
        }
    }

    // The options own a native handle and must outlive the session built from them.
    override fun close() {
        session.close()
        options.close()
    }

    private fun chwInput(bitmap: Bitmap): FloatBuffer {
        val pixels = IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        val plane = SIZE * SIZE
        val buffer = FloatBuffer.allocate(CHANNELS * plane)
        pixels.forEachIndexed { i, argb ->
            buffer.put(i, Color.red(argb) / MAX_CHANNEL)
            buffer.put(plane + i, Color.green(argb) / MAX_CHANNEL)
            buffer.put(2 * plane + i, Color.blue(argb) / MAX_CHANNEL)
        }
        return buffer
    }

    /** Input geometry the model was trained on. */
    companion object {
        /** Width and height of the model input, in pixels. */
        const val SIZE = 224
        private const val INPUT = "image"
        private const val CHANNELS = 3
        private const val MAX_CHANNEL = 255f
        private val SHAPE = longArrayOf(1, CHANNELS.toLong(), SIZE.toLong(), SIZE.toLong())
    }
}
