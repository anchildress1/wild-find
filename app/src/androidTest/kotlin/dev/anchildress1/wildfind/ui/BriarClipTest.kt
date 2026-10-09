package dev.anchildress1.wildfind.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every Briar sheet is packed and decodes; video states play once, rest, and play again. */
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
            val frameHeight = when {
                "$name.webp" in packed -> clip(name).intrinsicHeight
                "$name.png" in packed -> sheetFrameHeight(name, meta)
                else -> throw AssertionError("$name has no .webp or .png")
            }
            assertTrue(name, meta.getInt("figure_height") in 1..frameHeight)
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

    private fun sheetFrameHeight(name: String, meta: JSONObject): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open("briar/$name.png").use { BitmapFactory.decodeStream(it, null, bounds) }
        val columns = meta.getInt("columns")
        val rows = (meta.getInt("frames") + columns - 1) / columns
        val frameHeight = meta.getInt("frame_height")
        assertTrue(name, bounds.outWidth >= columns * meta.getInt("frame_width"))
        assertTrue(name, bounds.outHeight >= rows * frameHeight)
        return frameHeight
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
