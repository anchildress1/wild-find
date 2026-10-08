package dev.anchildress1.wildfind.core.sprite

import dev.anchildress1.wildfind.core.verify.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BriarStateTest {
    @Test
    fun `a find cheers, a miss or warning retries, and a held streak waits`() {
        assertEquals(BriarState.FOUND, BriarState.after(Verdict.Found))
        assertNull(BriarState.after(Verdict.Matching(1)))
        listOf(Verdict.Hazard, Verdict.NotPlant, Verdict.TapToFocus, Verdict.WalkCloser, Verdict.Guide)
            .forEach { assertEquals(BriarState.RETRY, BriarState.after(it)) }
    }

    @Test
    fun `every state names its packed sheet`() {
        assertEquals(
            listOf("welcome", "searching", "found", "retry", "complete"),
            BriarState.entries.map { it.sheet },
        )
    }
}
