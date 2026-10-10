package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** R5: the camera names what to find; Back and Skip keep the hunt moving. A real find needs a real plant. */
@RunWith(AndroidJUnit4::class)
class CameraTest : E2eTest() {
    @Test
    fun startLookingOpensTheCameraOnTheFirstPlantWithItsName() {
        startHuntList()
        val first = stopName(stops().first())
        tap(R.string.start_looking)
        waitForText(text(R.string.camera_find, first))
        assertTrue(has(hasText(text(R.string.camera_progress, 1, TARGETS))))
    }

    @Test
    fun theTopBarShowsTheTargetsDescriptionUnderItsName() {
        startHuntList()
        val first = savedHunt().progress.targets.first()
        val description = models().rows[first.row].description
        assertNotNull("${models().rows[first.row].scientific} has no description", description)
        tap(R.string.start_looking)
        waitForText(text(R.string.camera_find, first.common))
        // A plain text node in the merged tree is one TalkBack reads.
        waitForText(description!!)
    }

    @Test
    fun tappingAStopOpensTheCameraOnThatPlant() {
        startHuntList()
        val third = stops()[2]
        tap(third)
        waitForText(text(R.string.camera_find, stopName(third)))
        assertTrue(has(hasText(text(R.string.camera_progress, TARGETS, TARGETS))))
    }

    @Test
    fun captureIsReadyOnceTheModelsLoadAndTheRuleStaysOnScreen() {
        startHuntList()
        tap(R.string.start_looking)
        waitFor(hasText(text(R.string.capture)) and hasClickAction() and isEnabled(), timeoutMs = NETWORK_TIMEOUT)
        assertTrue(has(hasContentDescription(text(R.string.leave_it_rule))))
    }

    @Test
    fun backToYourHuntReturnsToTheHuntList() {
        startHuntList()
        tap(R.string.start_looking)
        tap(R.string.back_to_hunt)
        waitForText(text(R.string.start_looking))
    }

    @Test
    fun thePhonesBackKeyOnTheCameraReturnsToTheHuntList() {
        startHuntList()
        tap(R.string.start_looking)
        // Capture shows while the camera is still starting; back sent then can land before the screen is settled.
        waitFor(hasText(text(R.string.capture)) and hasClickAction() and isEnabled(), timeoutMs = NETWORK_TIMEOUT)
        compose.waitForIdle()
        pressBack()
        waitForText(text(R.string.start_looking))
    }

    @Test
    fun skipSwapsInAnotherPlantInTheSameSlot() {
        startHuntList()
        val before = stops().map(::stopName)
        tap(R.string.start_looking)
        waitForText(text(R.string.camera_find, before[0]))
        tap(R.string.skip_target)
        waitUntil(UI_TIMEOUT, "Skip kept ${before[0]}") {
            !has(hasText(text(R.string.camera_find, before[0]))) &&
                has(hasText(text(R.string.camera_find, "").trimEnd(), substring = true))
        }
        assertTrue(has(hasText(text(R.string.camera_progress, 1, TARGETS))))
        tap(R.string.back_to_hunt)
        waitForText(text(R.string.start_looking))
        val after = stops().map(::stopName)
        assertNotEquals(before[0], after[0])
        assertEquals("the other stops moved", before.drop(1), after.drop(1))
    }

    @Test
    fun skipPracticeLeavesTheGrassForTheHuntList() {
        startFirstHunt()
        tap(R.string.try_it)
        tap(R.string.skip_practice)
        waitForText(text(R.string.start_looking))
    }

    @Test
    fun backFromTheGrassCameraReturnsToThePractice() {
        startFirstHunt()
        tap(R.string.try_it)
        tap(R.string.back_to_hunt)
        waitForText(text(R.string.tutorial_title))
    }
}
