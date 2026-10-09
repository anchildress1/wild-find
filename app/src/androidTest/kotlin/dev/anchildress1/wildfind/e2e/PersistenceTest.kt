package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S38, R15: the current hunt outlives the app closing; an ended hunt doesn't. */
@RunWith(AndroidJUnit4::class)
class PersistenceTest : E2eTest() {
    @Test
    fun theHuntListSurvivesTheAppRestarting() {
        startHuntList()
        val before = stops()
        savedHunt()
        relaunch()
        waitForText(text(R.string.start_looking))
        assertEquals(before, stops())
    }

    @Test
    fun aPendingGrassPracticeSurvivesTheAppRestarting() {
        startFirstHunt()
        savedHunt()
        relaunch()
        waitForText(text(R.string.tutorial_title))
    }

    @Test
    fun aSkippedPlantStaysSwappedAfterTheAppRestarts() {
        startHuntList()
        tap(R.string.start_looking)
        tap(R.string.skip_target)
        tap(R.string.back_to_hunt)
        waitForText(text(R.string.start_looking))
        val swapped = stops()
        waitUntil(UI_TIMEOUT, "the swap was never saved") {
            savedHunt().progress.targets.map { it.common } == swapped.map(::stopName)
        }
        relaunch()
        waitForText(text(R.string.start_looking))
        assertEquals(swapped, stops())
    }

    @Test
    fun finishHuntShowsTheHuntCompleteScreenWithNoStarsEarned() {
        startHuntList()
        val names = stops().map(::stopName)
        tap(R.string.finish_hunt)
        waitForText(text(R.string.complete_title))
        assertTrue(has(hasContentDescription(plural(R.plurals.stars, 0, 0))))
        assertTrue(has(hasText(plural(R.plurals.complete_partial, TARGETS, 0, TARGETS))))
        names.forEach { assertTrue("$it isn't listed", has(hasText(it))) }
        assertEquals(TARGETS, nodes(hasText(text(R.string.still_out))).size)
        listOf(R.string.hunt_again, R.string.home).forEach { assertTrue(has(hasText(text(it)))) }
    }

    @Test
    fun homeAfterAHuntForgetsItAndARestartLandsOnStart() {
        startHuntList()
        tap(R.string.finish_hunt)
        tap(R.string.home)
        waitForText(text(R.string.start_title))
        relaunch()
        waitForText(text(R.string.start_button))
        assertFalse("an ended hunt came back", has(hasText(text(R.string.start_looking))))
    }

    @Test
    fun startAHuntFromHomeStartsAFreshHuntInTheSameArea() {
        startHuntList()
        tap(R.string.finish_hunt)
        tap(R.string.home)
        tap(R.string.start_button)
        awaitHunt(text(R.string.start_looking))
        assertEquals(TARGETS, stops().size)
        assertEquals(WEST_GEORGIA_LAT to WEST_GEORGIA_LNG, savedHunt().region.let { it.lat to it.lng })
    }
}
