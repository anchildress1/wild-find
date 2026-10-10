package dev.anchildress1.wildfind.core.verify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CaptureCueTest {
    @Test
    fun `each final verdict maps to one cue, and a running streak to none`() {
        assertEquals(CaptureCue.HAZARD, CaptureCue.of(Verdict.Hazard(0), fullFramePlant = true))
        assertEquals(CaptureCue.TAP_TO_FOCUS, CaptureCue.of(Verdict.TapToFocus, fullFramePlant = true))
        assertEquals(CaptureCue.FOUND, CaptureCue.of(Verdict.Found, fullFramePlant = true))
        assertEquals(CaptureCue.GET_CLOSER, CaptureCue.of(Verdict.WalkCloser, fullFramePlant = true))
        assertEquals(CaptureCue.KEEP_LOOKING, CaptureCue.of(Verdict.Guide, fullFramePlant = true))
        assertNull(CaptureCue.of(Verdict.Matching(2), fullFramePlant = true))
    }

    @Test
    fun `a plant outside the ring gets aimed, and no plant at all gets pointed at`() {
        assertEquals(CaptureCue.PUT_IN_CIRCLE, CaptureCue.of(Verdict.NotPlant, fullFramePlant = true))
        assertEquals(CaptureCue.POINT_AT_PLANT, CaptureCue.of(Verdict.NotPlant, fullFramePlant = false))
    }
}
