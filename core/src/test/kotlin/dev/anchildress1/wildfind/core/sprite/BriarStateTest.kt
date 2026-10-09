package dev.anchildress1.wildfind.core.sprite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BriarStateTest {
    @Test
    fun `every state names its packed sheet`() {
        assertEquals(
            listOf("opener", "warning", "warning", "complete", "complete"),
            BriarState.entries.map {
                it.sheet
            },
        )
        assertEquals(listOf(BriarState.COMPLETE), BriarState.entries.filter { it.loop })
        // A screen keeps replaying its own sheet, so Briar never swaps to idle's differently sized art.
        assertEquals(
            listOf(BriarState.OPENER, BriarState.WARNING, BriarState.WELCOME, BriarState.FOUND),
            BriarState.entries.filter { it.replayAfterMillis == 1_500L },
        )
    }
}
