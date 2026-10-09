package dev.anchildress1.wildfind.inference

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.frame.Pixels
import org.json.JSONObject

/** Decodes an image from the test APK's assets. */
fun testBitmap(path: String): Bitmap =
    InstrumentationRegistry.getInstrumentation().context.assets.open(path).use(BitmapFactory::decodeStream)

/** Parses the JSON asset at [path]. */
fun AssetManager.readJson(path: String): JSONObject = JSONObject(open(path).bufferedReader().use { it.readText() })

/** This bitmap's pixels in the layout the encoders take. */
fun Bitmap.pixels(): Pixels {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)
    return Pixels(width, height, argb)
}
