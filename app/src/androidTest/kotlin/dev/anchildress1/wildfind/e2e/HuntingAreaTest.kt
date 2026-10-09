package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.hunt.AppFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** R2, R7: the hunting area comes from the rough location or the built-in map, never a precise spot. */
@RunWith(AndroidJUnit4::class)
class HuntingAreaTest : E2eTest() {
    @Test
    fun theAreaChoiceOffersMyAreaOrTheMapAndSaysTheExactSpotStaysOnThePhone() {
        passOpener()
        listOf(R.string.region_use_location, R.string.region_pick_map).forEach {
            assertTrue("no \"${text(it)}\" button", has(hasText(text(it)) and hasClickAction()))
        }
        assertTrue(has(hasText(text(R.string.region_private))))
    }

    @Test
    fun huntHereStaysLockedUntilTheMapIsZoomedInToAboutTwelveDegrees() {
        passOpener()
        tap(R.string.region_pick_map)
        waitForText(text(R.string.map_title))
        // With location allowed the map can open zoomed on the phone's area, so start from the whole world.
        repeat(ZOOM_TAPS + 2) { tap(R.string.map_zoom_out) }
        waitFor(hasText(text(R.string.map_zoom_to_pick)) and isNotEnabled())
        // 360° → 22.5° across: still too wide to pick.
        repeat(ZOOM_TAPS - 1) { tap(R.string.map_zoom_in) }
        waitFor(hasText(text(R.string.map_zoom_to_pick)) and isNotEnabled())
        assertFalse(has(hasText(text(R.string.map_hunt_here))))
        tap(R.string.map_zoom_in)
        waitFor(hasText(text(R.string.map_hunt_here)) and isEnabled())
        assertTrue("the 1° arrow pad didn't show", has(hasContentDescription(text(R.string.map_north))))
    }

    @Test
    fun theMapSaysOnlyTheCrosshairsSpotGoesToINaturalist() {
        passOpener()
        tap(R.string.region_pick_map)
        waitForText(text(R.string.map_private))
    }

    @Test
    fun theArrowPadMovesTheCrosshairsOneWholeDegree() {
        passOpener()
        tap(R.string.region_pick_map)
        pickOnMap(WEST_GEORGIA_LAT, WEST_GEORGIA_LNG)
        tap(R.string.map_north)
        waitUntil(UI_TIMEOUT, "north didn't move one degree") {
            crosshairs() == (WEST_GEORGIA_LAT + 1) to WEST_GEORGIA_LNG
        }
        tap(R.string.map_west)
        waitUntil(UI_TIMEOUT, "west didn't move one degree") {
            crosshairs() == (WEST_GEORGIA_LAT + 1) to (WEST_GEORGIA_LNG - 1)
        }
    }

    @Test
    fun theMapNamesWestGeorgiaInWholeDegrees() {
        passOpener()
        tap(R.string.region_pick_map)
        pickOnMap(WEST_GEORGIA_LAT, WEST_GEORGIA_LNG)
        waitForText(WEST_GEORGIA)
    }

    @Test
    fun backOnTheMapReturnsToTheAreaChoice() {
        passOpener()
        tap(R.string.region_pick_map)
        waitForText(text(R.string.map_title))
        tap(R.string.back)
        waitForText(text(R.string.region_title))
    }

    @Test
    fun pickingWestGeorgiaOnTheMapStartsAHuntThere() {
        startHuntList()
        waitFor(hasText(WEST_GEORGIA, substring = true))
        assertEquals(WEST_GEORGIA_LAT to WEST_GEORGIA_LNG, savedHunt().region.let { it.lat to it.lng })
    }

    @Test
    fun useMyAreaStartsAHuntWithoutOpeningTheMap() {
        passOpener()
        tap(R.string.region_use_location)
        awaitHunt(text(R.string.tutorial_title))
        assertFalse(has(hasText(text(R.string.map_title))))
    }

    @Test
    fun afterLocationIsDeniedOnlyTheMapIsOfferedAndLocateIsGone() {
        closeApp()
        graph.store.save(AppFlags(openerSeen = true, locationDenied = true))
        relaunch()
        waitForText(text(R.string.region_pick_map))
        assertFalse("location was asked again", has(hasText(text(R.string.region_use_location))))
        tap(R.string.region_pick_map)
        waitForText(text(R.string.map_title))
        assertFalse("the map offers Locate", has(hasContentDescription(text(R.string.map_locate))))
    }

    private companion object {
        const val WEST_GEORGIA = "34°N, 85°W · Georgia"
    }
}
