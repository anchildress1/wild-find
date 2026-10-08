package dev.anchildress1.wildfind.core.hunt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HuntProgressTest {
    private val targets = listOf(Eligible(4, "water oak", 72), Eligible(9, "sweetgum", 183), Eligible(2, "redbud", 76))

    @Test
    fun `the tutorial comes first, then targets in any order, then the hunt is complete`() {
        var progress = HuntProgress.start(Hunt(tutorial = true, targets))
        assertThrows<IllegalStateException> { progress.targetFound(4) }

        progress = progress.tutorialPassed().targetFound(2)
        assertEquals(listOf(4, 9), progress.remaining.map { it.row })
        assertFalse(progress.complete)

        progress = progress.targetFound(9).targetFound(4)
        assertTrue(progress.complete)
        assertEquals(3, progress.stars)
    }

    @Test
    fun `a hunt without the tutorial starts on its targets`() {
        val progress = HuntProgress.start(Hunt(tutorial = false, targets))

        assertThrows<IllegalStateException> { progress.tutorialPassed() }
        assertEquals(1, progress.targetFound(9).stars)
    }

    @Test
    fun `finding a target twice counts one star`() {
        assertEquals(1, HuntProgress.start(Hunt(false, targets)).targetFound(9).targetFound(9).stars)
    }

    @Test
    fun `only the hunt's own targets can be found or restored`() {
        assertThrows<IllegalArgumentException> { HuntProgress.start(Hunt(false, targets)).targetFound(7) }
        assertThrows<IllegalArgumentException> { HuntProgress(false, targets, setOf(7)) }
        assertThrows<IllegalArgumentException> { HuntProgress(false, targets + targets[0]) }
    }

    @Test
    fun `app flags start unset`() {
        assertEquals(AppFlags(openerSeen = false, tutorialDone = false), AppFlags())
    }
}
