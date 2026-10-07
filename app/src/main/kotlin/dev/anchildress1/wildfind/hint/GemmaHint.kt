package dev.anchildress1.wildfind.hint

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.anchildress1.wildfind.core.hint.HintPrompts
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Gemma 4 E2B on LiteRT-LM for level-2 hints: one engine per session, a fresh conversation per call.
 *
 * Text and vision both run on the GPU, as measured on Day 1. Every call blocks for seconds; never call one on
 * the main thread.
 *
 * @param model the verified `.litertlm` file
 * @param cacheDir writable directory for the GPU program cache, which cuts later loads from about 10 s to 4 s
 */
class GemmaHint(model: File, cacheDir: File) : AutoCloseable {
    private val engine = Engine(
        EngineConfig(
            modelPath = model.path,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            maxNumImages = 1,
            cacheDir = cacheDir.path,
        ),
    )

    /** Loads the model; seconds of work. */
    fun load() = engine.initialize()

    /**
     * Runs one throwaway scene call on a blank frame. The first vision call after a load pays about 3 s of one-time
     * setup on the S24 Ultra (4.1-4.8 s against 1.3 s warm), so this keeps that off the kid's first hint tap.
     */
    fun warmUp() {
        scene(BLANK_JPEG)
    }

    /** Gemma's raw scene-call reply for a JPEG camera frame; [HintPrompts.parseTags] reads the tags from it. */
    fun scene(jpeg: ByteArray): String {
        require(jpeg.isNotEmpty()) { "empty JPEG" }
        return ask(Contents.of(Content.ImageBytes(jpeg), Content.Text(HintPrompts.scene())), TAG_TOKENS)
    }

    /** Gemma's raw reply to a hint [prompt]; the R6 guards decide whether a kid sees it. */
    fun hint(prompt: String): String = ask(Contents.of(Content.Text(prompt)), HINT_TOKENS)

    override fun close() = engine.close()

    private fun ask(contents: Contents, maxTokens: Int): String =
        engine.createConversation(ConversationConfig(maxOutputToken = maxTokens)).use { conversation ->
            conversation.sendMessage(contents).contents.contents.filterIsInstance<Content.Text>().joinToString("") {
                it.text
            }
        }

    private companion object {
        // Caps on output length, so a rambling reply can't stretch the latency a kid waits through.
        const val TAG_TOKENS = 48
        const val HINT_TOKENS = 64
        const val BLANK_SIZE = 256
        const val BLANK_GRAY = 0xFF808080.toInt()
        const val JPEG_QUALITY = 90

        val BLANK_JPEG: ByteArray by lazy {
            val bitmap = createBitmap(BLANK_SIZE, BLANK_SIZE).apply {
                eraseColor(BLANK_GRAY)
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        }
    }
}
