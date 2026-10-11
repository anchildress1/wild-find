package dev.anchildress1.wildfind.core.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdjustmentSequenceTest {
    private val sequence = AdjustmentSequence()

    @Test
    fun `the newest adjustment's failure shows and a retry clears it`() {
        val focus = sequence.begin()
        assertTrue(sequence.settle(focus, failure = true))
        assertTrue(sequence.failed)
        sequence.begin()
        assertFalse(sequence.failed)
    }

    @Test
    fun `an older focus success cannot clear a newer zoom failure`() {
        val focus = sequence.begin()
        val zoom = sequence.begin()
        assertTrue(sequence.settle(zoom, failure = true))
        assertFalse(sequence.settle(focus, failure = false))
        assertTrue(sequence.failed)
    }

    @Test
    fun `an older zoom failure cannot overwrite a newer focus success`() {
        val zoom = sequence.begin()
        val focus = sequence.begin()
        assertTrue(sequence.settle(focus, failure = false))
        assertFalse(sequence.settle(zoom, failure = true))
        assertFalse(sequence.failed)
    }

    @Test
    fun `a completion that arrives after a newer registration is dropped before it reports`() {
        val older = sequence.begin()
        val newer = sequence.begin()
        assertFalse(sequence.settle(older, failure = true))
        assertFalse(sequence.failed)
        assertTrue(sequence.settle(newer, failure = false))
        assertFalse(sequence.failed)
    }
}
