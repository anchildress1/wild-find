package dev.anchildress1.wildfind.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.inference.readJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every Briar state's video is packed and decodes, and a play-once clip plays again. */
@RunWith(AndroidJUnit4::class)
class BriarClipTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun clip(name: String) =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, "briar/$name.webp")) as AnimatedImageDrawable

    @Test
    fun everySheetIsPackedWithItsFigureHeight() {
        val packed = assets.list("briar")!!.toSet()
        (BriarState.entries.map { it.sheet } + BriarState.IDLE).toSet().forEach { name ->
            assertTrue("$name.json missing", "$name.json" in packed)
            val meta = assets.readJson("briar/$name.json")
            assertTrue("$name.webp missing", "$name.webp" in packed)
            val drawable = clip(name)
            assertTrue(name, meta.getInt("figure_height") in 1..drawable.intrinsicHeight)
            // The player reserves Briar's box from these before the clip decodes.
            assertEquals(
                name,
                drawable.intrinsicWidth to drawable.intrinsicHeight,
                meta.getInt("width") to meta.getInt("height"),
            )
        }
    }

    @Test
    fun aFinishedPlayOnceClipStartsAgain() {
        val drawable = clip(BriarState.IDLE)
        val ends = CountDownLatch(2)
        val main = Handler(Looper.getMainLooper())
        main.post {
            drawable.repeatCount = 0
            drawable.registerAnimationCallback(
                object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable) {
                        ends.countDown()
                        if (ends.count == 1L) drawable.start()
                    }
                },
            )
            drawable.start()
        }
        // Each play draws only when asked, so drive draws until both plays end.
        val canvas = Canvas(
            Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888),
        )
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (ends.count > 0 && System.nanoTime() < deadline) {
            val drawn = CountDownLatch(1)
            main.post {
                drawable.draw(canvas)
                drawn.countDown()
            }
            drawn.await(1, TimeUnit.SECONDS)
            Thread.sleep(FRAME_MS)
        }
        assertEquals("both plays ended", 0L, ends.count)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
