package dev.anchildress1.wildfind.core.sprite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BriarStateTest {
    @Test
    fun `every state names its packed sheet`() {
        assertEquals(listOf("opener", "welcome", "found", "complete"), BriarState.entries.map { it.sheet })
        // A screen keeps replaying its own sheet, so Briar never swaps to idle's differently sized art.
        assertEquals(
            listOf(BriarState.OPENER, BriarState.WELCOME, BriarState.FOUND),
            BriarState.entries.filter { it.replayAfterMillis == 1_500L },
        )
    }
}
