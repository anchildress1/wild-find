package dev.anchildress1.wildfind.core.verify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class VerifyTest {
    private val close = Focus(Focus.AF_PASSIVE_FOCUSED, 4f, 1f)
    private val match = FrameEvidence(hazard = false, reticlePlant = true, focus = close, goalMet = true)

    @ParameterizedTest(name = "af={0} diopters={1} zoom={2}")
    @CsvSource(
        "2, 4.0, 1.0, true, true",
        "4, 2.0, 1.0, true, true",
        "4, 1.0, 2.0, true, true",
        "4, 1.99, 1.0, true, false",
        "4, 0.0, 1.0, true, false",
        "1, 4.0, 1.0, false, false",
        "3, 4.0, 1.0, false, false",
        "4, -0.5, 1.0, false, false",
        "4, NaN, 1.0, false, false",
        ", 4.0, 1.0, false, false",
    )
    fun `focus is read only while focused, and close at diopters times zoom of 2`(
        af: Int?,
        diopters: Float,
        zoom: Float,
        focused: Boolean,
        isClose: Boolean,
    ) {
        val focus = Focus(af, diopters, zoom)

        assertEquals(focused, focus.isFocused)
        assertEquals(isClose, focus.isClose)
    }

    @Test
    fun `zoom comes from the ratio control, else the crop region, else nowhere`() {
        assertEquals(2.5f, Focus.zoomRatio(2.5f, 4000, 1000))
        assertEquals(4f, Focus.zoomRatio(null, 4000, 1000))
        assertNull(Focus.zoomRatio(null, 4000, null))
        assertNull(Focus.zoomRatio(null, 4000, 0))
        // Before the camera reports its active array, a crop region alone would read as 0x zoom.
        assertNull(Focus.zoomRatio(null, 0, 1000))
    }

    @Test
    fun `a missing distance is not a focused reading`() {
        assertFalse(Focus(Focus.AF_FOCUSED_LOCKED, null, 1f).isFocused)
        assertFalse(Focus(Focus.AF_FOCUSED_LOCKED, null, 1f).isClose)
    }

    @Test
    fun `rows are checked in PRD order, first match wins`() {
        val far = Focus(Focus.AF_PASSIVE_FOCUSED, 0.5f, 1f)
        val unfocused = Focus(1, 4f, 1f)
        fun verdict(e: FrameEvidence) = VerifyStreak().next(e)

        assertEquals(Verdict.Hazard, verdict(match.copy(hazard = true, reticlePlant = false, focus = null)))
        assertEquals(Verdict.NotPlant, verdict(match.copy(reticlePlant = false, focus = null)))
        assertEquals(Verdict.TapToFocus, verdict(match.copy(focus = null)))
        assertEquals(Verdict.TapToFocus, verdict(match.copy(focus = unfocused)))
        assertEquals(Verdict.WalkCloser, verdict(match.copy(focus = far)))
        assertEquals(Verdict.Matching(1), verdict(match))
        assertEquals(Verdict.Guide, verdict(match.copy(goalMet = false)))
    }

    @Test
    fun `three matching frames in a row find the target, then a new streak starts`() {
        val streak = VerifyStreak()

        assertEquals(
            listOf(Verdict.Matching(1), Verdict.Matching(2), Verdict.Found, Verdict.Matching(1)),
            List(4) { streak.next(match) },
        )
    }

    @Test
    fun `any other verdict breaks the streak`() {
        val streak = VerifyStreak()
        streak.next(match)
        streak.next(match)

        assertEquals(Verdict.WalkCloser, streak.next(match.copy(focus = Focus(Focus.AF_FOCUSED_LOCKED, 1f, 1f))))
        assertEquals(Verdict.Matching(1), streak.next(match))
    }

    @Test
    fun `focus track returns the reading of the exact capture, never a neighbor`() {
        val track = FocusTrack(capacity = 2)
        track.record(100, close)
        track.record(200, Focus(1, 0.2f, 1f))

        assertEquals(close, track.at(100))
        assertNull(track.at(150))

        track.record(300, close)
        assertNull(track.at(100))
        assertTrue(track.at(300) == close)
    }

    @Test
    fun `focus track starts empty and rejects no capacity`() {
        assertNull(FocusTrack().at(0))
        assertThrows<IllegalArgumentException> { FocusTrack(0) }
    }
}
