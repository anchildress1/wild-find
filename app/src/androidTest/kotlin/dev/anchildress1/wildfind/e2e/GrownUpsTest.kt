package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.inat.InatLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** R1, R7: the grown-ups page states the privacy facts, shows the area, and replays the opener. */
@RunWith(AndroidJUnit4::class)
class GrownUpsTest : E2eTest() {
    private fun openGrownUps() {
        startHuntList()
        tap(R.string.grown_ups)
        waitForText(text(R.string.privacy))
    }

    @Test
    fun grownUpsListsNoAccountPhotosStayLocalRoughAreaAndNothingDownloads() {
        openGrownUps()
        listOf(R.string.privacy_account, R.string.privacy_photos, R.string.privacy_location, R.string.privacy_network)
            .forEach { assertTrue("missing \"${text(it)}\"", has(hasText(text(it)))) }
        assertTrue("Nothing downloads after install" in text(R.string.privacy_network))
    }

    @Test
    fun grownUpsDisclosesWhatINaturalistSeesAndTheAgeRule() {
        openGrownUps()
        listOf(
            R.string.inat_title,
            R.string.inat_asks,
            R.string.inat_ip,
            R.string.inat_terms,
            R.string.inat_account,
            R.string.inat_nothing,
        ).forEach { assertTrue("missing \"${text(it)}\"", has(hasText(text(it)))) }
        assertTrue("13" in text(R.string.inat_terms) && "parent" in text(R.string.inat_terms))
    }

    @Test
    fun cacheMyAreaSavesEveryMonthOfTheHuntingAreaForPlayWithoutSignal() {
        openGrownUps()
        assertTrue(has(hasText(text(R.string.cache_how))))
        tap(R.string.cache_button)
        waitFor(hasText(text(R.string.cache_done, ""), substring = true), timeoutMs = CACHE_TIMEOUT)
        val locale = InatLocale.of(Locale.getDefault().toLanguageTag())
        val missing = (1..MONTHS).filter {
            graph.store.cached(CacheKey(models().tableVersion, area.region, locale, it, CacheKey.RADIUS_KM)) == null
        }
        assertEquals("months not saved on the phone", emptyList<Int>(), missing)
    }

    @Test
    fun grownUpsShowsTheHuntingAreaInWholeDegrees() {
        openGrownUps()
        val detail = text(R.string.hunting_area_detail, "").removePrefix(" · ")
        waitFor(hasText(area.degrees, substring = true) and hasText(detail, substring = true))
    }

    @Test
    fun watchTheSafetyIntroAgainReplaysTheOpenerAndDoneReturnsToGrownUps() {
        openGrownUps()
        tap(R.string.replay_opener)
        waitForText(text(R.string.rule_leave))
        assertTrue(has(hasText(text(R.string.opener_intro))))
        assertFalse("a replay says Let's go", has(hasText(text(R.string.opener_go))))
        tap(R.string.opener_done)
        waitForText(text(R.string.privacy))
    }

    @Test
    fun thePhonesBackKeyOnAReplayedOpenerReturnsToGrownUps() {
        openGrownUps()
        tap(R.string.replay_opener)
        waitForText(text(R.string.opener_done))
        pressBack()
        waitForText(text(R.string.privacy))
    }

    @Test
    fun huntingAreaOpensTheMapAndBackReturnsToGrownUps() {
        openGrownUps()
        tap(R.string.hunting_area)
        waitForText(text(R.string.map_title))
        tap(R.string.back)
        waitForText(text(R.string.privacy))
    }

    @Test
    fun backFromGrownUpsReturnsToTheHuntList() {
        openGrownUps()
        tap(R.string.back)
        waitForText(text(R.string.start_looking))
    }

    @Test
    fun theGrassPracticeAlsoOffersGrownUps() {
        startFirstHunt()
        tap(R.string.grown_ups)
        waitForText(text(R.string.privacy))
        pressBack()
        waitForText(text(R.string.tutorial_title))
    }

    private companion object {
        const val CACHE_TIMEOUT = 240_000L
        const val MONTHS = 12
    }
}
