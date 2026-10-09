package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** R2, R3, R4: the first hunt opens with grass, then 3 local plants from 3 genera. */
@RunWith(AndroidJUnit4::class)
class HuntStartTest : E2eTest() {
    @Test
    fun theFirstEverHuntOpensWithTheGrassPracticeAndTheRule() {
        startFirstHunt()
        listOf(R.string.practice, R.string.tutorial_point, R.string.tutorial_then).forEach {
            assertTrue("missing \"${text(it)}\"", has(hasText(text(it))))
        }
        assertTrue(has(hasContentDescription(text(R.string.leave_it_rule))))
    }

    @Test
    fun tryItOpensTheCameraOnGrassWithSkipPractice() {
        startFirstHunt()
        tap(R.string.try_it)
        waitForText(text(R.string.camera_find, text(R.string.grass)))
        assertTrue(has(hasText(text(R.string.practice))))
        waitFor(hasText(text(R.string.skip_practice)) and hasClickAction() and isEnabled())
    }

    @Test
    fun skipPracticeGoesStraightToAHuntOfThreePlants() {
        startHuntList()
        assertEquals(TARGETS, stops().size)
        assertFalse(has(hasText(text(R.string.tutorial_title))))
    }

    @Test
    fun theHuntListsThreePlantsFromThreeGeneraWithNamesOfThreeWordsOrFewer() {
        startHuntList()
        val names = stops().map(::stopName)
        assertEquals("stops: ${stops()}", TARGETS, names.size)
        names.forEach { assertTrue("\"$it\" is longer than 3 words", it.split(' ').size <= MAX_WORDS) }
        val rows = models().rows
        val targets = savedHunt().progress.targets
        assertEquals(names.toSet(), targets.map { it.common }.toSet())
        assertEquals("two targets share a genus", TARGETS, targets.map { rows[it.row].genus }.distinct().size)
        targets.forEach { assertTrue("${rows[it.row].scientific} is toxic or a hazard", rows[it.row].playable) }
    }

    @Test
    fun theHuntShowsTheAreaTheMonthAndEveryStopNotFoundYet() {
        startHuntList()
        val month = LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        waitFor(hasText(month, substring = true))
        val notYet = text(R.string.stop_plain_open, 0, "").substringAfter("0, ")
        stops().forEach { assertTrue("$it already counts as found", notYet in it) }
        assertTrue(has(hasText(text(R.string.start_looking)) and hasClickAction()))
        assertTrue(has(hasContentDescription(text(R.string.leave_it_rule))))
    }

    @Test
    fun aSkippedPracticeNeverComesBackOnTheNextHunt() {
        startHuntList()
        tap(R.string.finish_hunt)
        tap(R.string.hunt_again)
        awaitHunt(plural(R.plurals.hunt_title, TARGETS, TARGETS))
        assertFalse("the grass practice came back", has(hasText(text(R.string.tutorial_title))))
    }

    private companion object {
        const val MAX_WORDS = 3
    }
}
