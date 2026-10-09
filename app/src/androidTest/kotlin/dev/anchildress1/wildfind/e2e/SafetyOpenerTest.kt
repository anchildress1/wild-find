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

/** R1: the safety opener comes first, once, and stays replayable. */
@RunWith(AndroidJUnit4::class)
class SafetyOpenerTest : E2eTest() {
    @Test
    fun firstLaunchOpensOnTheSafetyRuleWithBriarWarning() {
        waitForText(text(R.string.rule_leave))
        assertEquals(
            "Look. Photograph. Leave it where it grows.",
            listOf(R.string.rule_look, R.string.rule_photograph, R.string.rule_leave).joinToString(" ") { text(it) },
        )
        listOf(R.string.rule_look, R.string.rule_photograph, R.string.rule_leave).forEach {
            assertTrue("missing \"${text(it)}\"", has(hasText(text(it))))
        }
        assertTrue("Briar isn't warning", has(hasContentDescription(text(R.string.briar_warning))))
    }

    @Test
    fun theOpenerNamesBeesSnakesAndPoisonIvy() {
        waitForText(text(R.string.opener_intro))
        val intro = text(R.string.opener_intro).lowercase()
        listOf("bees", "snakes", "poison ivy").forEach { assertTrue("the opener never names $it", it in intro) }
    }

    @Test
    fun letsGoMovesOnToChoosingWhereToHunt() {
        passOpener()
        assertFalse(has(hasText(text(R.string.opener_go))))
    }

    @Test
    fun theOpenerNeverShowsAgainAfterTheAppRestarts() {
        passOpener()
        relaunch()
        waitForText(text(R.string.region_title))
        assertFalse("the opener came back", has(hasText(text(R.string.opener_go))))
    }
}
