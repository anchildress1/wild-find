package dev.anchildress1.wildfind.core.frame

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The Kotlin frame path against Pillow's own crops and resizes from `make crop-reference`, pixel for pixel. */
class PillowParityTest {
    private val upright = png("upright.png")

    @ParameterizedTest(name = "{0}")
    @CsvSource("down.png", "narrow.png", "short.png")
    fun `resizes on both axes and on one axis only match Pillow`(name: String) {
        val expected = png(name)

        assertTrue(sameRgb(expected, Bicubic.resize(upright, expected.width, expected.height)))
    }

    @ParameterizedTest(name = "{0} at rotation {1}")
    @CsvSource(
        "reticle.png, upright.png, 0",
        "reticle.png, rot90.png, 90",
        "reticle.png, rot180.png, 180",
        "reticle.png, rot270.png, 270",
        "full.png, upright.png, 0",
        "full.png, rot90.png, 90",
        "full.png, rot180.png, 180",
        "full.png, rot270.png, 270",
    )
    fun `crop and resize from a camera buffer match Day 1's Pillow crop`(crop: String, raw: String, rotation: Int) {
        val frame = cameraFrame(png(raw), rotation, left = 2, top = 7, padding = 12)
        val reticle = crop == "reticle.png"
        val box = if (reticle) Crops.reticle(frame.width, frame.height) else Crops.fullFrame(frame.width, frame.height)

        val actual = Bicubic.resize(frame.upright(box), Crops.MODEL_SIZE, Crops.MODEL_SIZE)

        assertEquals(upright.width to upright.height, frame.width to frame.height)
        assertTrue(sameRgb(png(crop), actual), "$crop from $raw differs from Pillow")
    }
}
