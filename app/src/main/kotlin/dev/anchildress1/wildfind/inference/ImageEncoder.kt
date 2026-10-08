package dev.anchildress1.wildfind.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.anchildress1.wildfind.core.frame.Crops
import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.frame.blue
import dev.anchildress1.wildfind.core.frame.green
import dev.anchildress1.wildfind.core.frame.red
import dev.anchildress1.wildfind.core.verify.ImageEmbedder
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * ONNX image encoder for BioCLIP 2.5 Mobile and the TinyCLIP plant gate: a 224x224 RGB image in, a unit embedding out.
 *
 * Both graphs take an `image` input of plain 0..1 RGB and apply their own normalization.
 */
class ImageEncoder(model: ByteBuffer) :
    ImageEmbedder,
    AutoCloseable {
    private val env = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    private val options = OrtSession.SessionOptions()
    private val session = env.createSession(model, options)

    /** Embeds [pixels], which must be exactly [Crops.MODEL_SIZE] square; resizing and cropping are the caller's job. */
    override fun embed(pixels: Pixels): FloatArray {
        require(pixels.width == SIZE && pixels.height == SIZE) {
            "expected ${SIZE}x$SIZE, got ${pixels.width}x${pixels.height}"
        }
        OnnxTensor.createTensor(env, chwInput(pixels), SHAPE).use { input ->
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

    private fun chwInput(pixels: Pixels): FloatBuffer {
        val plane = SIZE * SIZE
        val buffer = FloatBuffer.allocate(CHANNELS * plane)
        pixels.argb.forEachIndexed { i, argb ->
            buffer.put(i, red(argb) / MAX_CHANNEL)
            buffer.put(plane + i, green(argb) / MAX_CHANNEL)
            buffer.put(2 * plane + i, blue(argb) / MAX_CHANNEL)
        }
        return buffer
    }

    private companion object {
        const val SIZE = Crops.MODEL_SIZE
        private const val INPUT = "image"
        private const val CHANNELS = 3
        private const val MAX_CHANNEL = 255f
        private val SHAPE = longArrayOf(1, CHANNELS.toLong(), SIZE.toLong(), SIZE.toLong())
    }
}
