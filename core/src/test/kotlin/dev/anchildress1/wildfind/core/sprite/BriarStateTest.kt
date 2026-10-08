package dev.anchildress1.wildfind.core.sprite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BriarStateTest {
    @Test
    fun `every state names its packed sheet`() {
        assertEquals(listOf("opener", "welcome", "found", "complete"), BriarState.entries.map { it.sheet })
        assertEquals(
            mapOf(BriarState.OPENER to 1_500L),
            BriarState.entries.mapNotNull { s ->
                s.replayAfterMillis?.let {
                    s to
                        it
                }
            }.toMap(),
        )
    }
}
