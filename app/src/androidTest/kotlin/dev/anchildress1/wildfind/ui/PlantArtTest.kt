package dev.anchildress1.wildfind.ui

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.hunt.PlantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every plant type a target can carry, and none, has its picture in the APK, so no tile ever comes up empty. */
@RunWith(AndroidJUnit4::class)
class PlantArtTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test
    fun everyPlantTypeAndTheUntypedPlantHaveASquarePictureWithTransparency() {
        // "plant" is the picture PlantArt draws for a species the build found no type for.
        (PlantType.entries.map { it.key } + "plant").forEach { key ->
            val bitmap = assets.open("plants/$key.webp").use(BitmapFactory::decodeStream)
            assertEquals(key, bitmap.width, bitmap.height)
            assertTrue(key, bitmap.hasAlpha())
        }
    }

    @Test
    fun theFindStarIsASquarePictureWithTransparency() {
        val star = assets.open("star.webp").use(BitmapFactory::decodeStream)
        assertEquals(star.width, star.height)
        assertTrue(star.hasAlpha())
    }
}
