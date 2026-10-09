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
    fun `a skip swaps in the next species and sends the skipped one to the back`() {
        val queue = listOf(Eligible(7, "mistflower", 362), Eligible(8, "beautyberry", 230))
        val progress = HuntProgress(false, targets, queue = queue).skip(9, genera::getValue)

        assertEquals(listOf(4, 7, 2), progress.targets.map { it.row })
        assertEquals(listOf(8, 9), progress.queue.map { it.row })
        assertEquals(listOf(4, 8, 2), progress.skip(7, genera::getValue).targets.map { it.row })
        assertEquals(listOf(9, 7), progress.skip(7, genera::getValue).queue.map { it.row })
    }

    @Test
    fun `with an empty queue a skip changes nothing, and only open targets skip`() {
        val progress = HuntProgress.start(Hunt(false, targets))

        assertEquals(progress, progress.skip(9, genera::getValue))
        assertThrows<IllegalStateException> { HuntProgress.start(Hunt(true, targets)).skip(9, genera::getValue) }
        assertThrows<IllegalArgumentException> { progress.skip(7, genera::getValue) }
        assertThrows<IllegalArgumentException> { progress.targetFound(9).skip(9, genera::getValue) }
    }

    @Test
    fun `a skip passes over a plant whose genus is already on screen`() {
        // Row 6 is a second oak: with the water oak (row 4) on screen it would let one oak photo earn two stars.
        val queue = listOf(Eligible(6, "white oak", 90), Eligible(7, "mistflower", 362))
        val progress = HuntProgress(false, targets, queue = queue).skip(9, genera::getValue)

        assertEquals(listOf(4, 7, 2), progress.targets.map { it.row })
        assertEquals(listOf(6, 9), progress.queue.map { it.row })
    }

    @Test
    fun `with only same-genus plants queued a skip changes nothing`() {
        val progress = HuntProgress(false, targets, queue = listOf(Eligible(6, "white oak", 90)))

        assertEquals(progress, progress.skip(9, genera::getValue))
        assertFalse(progress.canSkip(9, genera::getValue))
    }

    @Test
    fun `a target can skip only while a replacement waits and it is still open`() {
        val progress = HuntProgress(false, targets, queue = listOf(Eligible(7, "mistflower", 362)))

        assertTrue(progress.canSkip(9, genera::getValue))
        assertFalse(HuntProgress(false, targets).canSkip(9, genera::getValue))
        assertFalse(progress.targetFound(9).canSkip(9, genera::getValue))
        assertFalse(HuntProgress(true, targets, queue = progress.queue).canSkip(9, genera::getValue))
    }

    private val genera = mapOf(
        4 to "Quercus",
        9 to "Liquidambar",
        2 to "Cercis",
        6 to "Quercus",
        7 to "Conoclinium",
        8 to "Callicarpa",
    )
}
