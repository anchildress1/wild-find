package dev.anchildress1.wildfind.ui

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.hunt.PlantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every plant type a target can carry has its picture in the APK, so no tile ever comes up empty. */
@RunWith(AndroidJUnit4::class)
class PlantArtTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test
    fun everyPlantTypeHasASquarePictureWithTransparency() {
        PlantType.entries.forEach { type ->
            val bitmap = assets.open("plants/${type.key}.webp").use(BitmapFactory::decodeStream)
            assertEquals(type.key, bitmap.width, bitmap.height)
            assertTrue(type.key, bitmap.hasAlpha())
        }
    }

    @Test
    fun theFindStarIsASquarePictureWithTransparency() {
        val star = assets.open("star.webp").use(BitmapFactory::decodeStream)
        assertEquals(star.width, star.height)
        assertTrue(star.hasAlpha())
    }
}
