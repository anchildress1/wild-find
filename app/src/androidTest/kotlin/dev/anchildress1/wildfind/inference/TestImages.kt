package dev.anchildress1.wildfind.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.frame.Pixels

/** Decodes an image from the test APK's assets. */
fun testBitmap(path: String): Bitmap =
    InstrumentationRegistry.getInstrumentation().context.assets.open(path).use(BitmapFactory::decodeStream)

/** This bitmap's pixels in the layout the encoders take. */
fun Bitmap.pixels(): Pixels {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)
    return Pixels(width, height, argb)
}
