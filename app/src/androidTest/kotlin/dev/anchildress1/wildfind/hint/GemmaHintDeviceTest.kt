package dev.anchildress1.wildfind.hint

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.hint.HintPrompts
import dev.anchildress1.wildfind.download.GemmaDownloadService
import dev.anchildress1.wildfind.inference.testBitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Level-2 hint on the phone: Gemma loads from the verified download and answers a scene call and a hint call. */
@RunWith(AndroidJUnit4::class)
class GemmaHintDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sceneTagsThenHintFromTheFixture() {
        val model = requireNotNull(GemmaDownloadService.readyModel(context)) { "Gemma missing; run make push-models" }
        val jpeg = ByteArrayOutputStream().also {
            testBitmap("reference/fixture.png").compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()

        GemmaHint(model, context.cacheDir).use { gemma ->
            val loaded = timed { gemma.load() }
            val (scene, sceneMs) = timedResult { gemma.scene(jpeg) }
            val tags = HintPrompts.parseTags(scene)
            val prompt = HintPrompts.levelTwo("oak", "Oaks grow in yards, parks, and along the woods edge.", tags)
            val (hint, hintMs) = timedResult { gemma.hint(prompt) }
            Log.i(TAG, "load $loaded ms, scene $sceneMs ms $tags from: $scene")
            Log.i(TAG, "hint $hintMs ms: $hint")

            // The fixture is a close-up of oak leaves, so an empty tag list is a valid answer; a reply with no list
            // at all means the scene prompt's output format broke.
            assertTrue("no tag list in the scene reply: $scene", '[' in scene)
            assertTrue("empty hint", hint.isNotBlank())
        }
    }

    private fun timed(block: () -> Unit): Long = timedResult(block).second

    private fun <T> timedResult(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / NANOS_PER_MS
    }

    private companion object {
        const val TAG = "GemmaHintDevice"
        const val JPEG_QUALITY = 90
        const val NANOS_PER_MS = 1_000_000
    }
}
