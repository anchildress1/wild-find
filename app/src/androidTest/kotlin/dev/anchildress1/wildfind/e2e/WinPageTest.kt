package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** R15: on the hunt-complete page each find wears its star over its plant, and Briar shows without scrolling. */
@RunWith(AndroidJUnit4::class)
class WinPageTest : E2eTest() {
    // A hunt with every target already found, as a saved hunt, so the page opens without three real plants.
    private fun openWinPage() {
        startHuntList()
        val saved = savedHunt()
        val done = saved.copy(progress = saved.progress.copy(found = saved.progress.targets.map { it.row }.toSet()))
        closeApp()
        graph.store.save(done, models().tableVersion)
        relaunch()
        waitForText(text(R.string.complete_title))
    }

    @Test
    fun everyFindWearsOneStarCenteredOnItsPlant() {
        openWinPage()
        val stars = nodes(hasContentDescription(text(R.string.one_star)), merged = false)
        assertEquals(TARGETS, stars.size)
        // Each tile's status line is centered under its plant, so a star over the plant shares its center.
        val labels = nodes(hasText(text(R.string.found_label))).map { it.boundsInRoot }
        stars.map { it.boundsInRoot }.forEach { star ->
            val label = labels.minBy { abs(it.center.x - star.center.x) }
            assertTrue(
                "a star sits off its plant's middle",
                abs(label.center.x - star.center.x) < star.width * OFF_CENTER,
            )
        }
    }

    @Test
    fun briarSitsAboveTheButtonsWithoutScrolling() {
        openWinPage()
        val briar = nodes(hasContentDescription(text(R.string.briar_complete))).first().boundsInRoot
        val buttons = nodes(hasText(text(R.string.hunt_again))).first().boundsInRoot
        assertTrue("Briar runs under the buttons: ${briar.bottom} past ${buttons.top}", briar.bottom <= buttons.top)
        assertTrue("Briar starts off the top of the screen", briar.top >= 0f)
    }

    private companion object {
        const val OFF_CENTER = 0.1f
    }
}
