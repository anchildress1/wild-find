package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The hint sheet: a Hint button on the camera opens hint 1, steps through the rest, and Keep looking goes back. */
@RunWith(AndroidJUnit4::class)
class HintSheetTest : E2eTest() {
    // "Hint 1 of 3" without its total, which depends on how many hints the plant has.
    private fun headerAt(n: Int) = text(R.string.hint_n_of, n, 0).removeSuffix("0")

    private fun openFirstHint() {
        startHuntList()
        tap(R.string.start_looking)
        waitFor(hasText(text(R.string.hint)) and hasClickAction() and isEnabled(), timeoutMs = NETWORK_TIMEOUT)
        tap(R.string.hint)
        waitFor(hasText(headerAt(1), substring = true))
    }

    @Test
    fun theHintButtonOpensHintOneAndKeepLookingReturnsToTheCamera() {
        openFirstHint()
        assertTrue(has(hasText(text(R.string.keep_looking))))
        assertFalse("Capture hides behind the sheet", has(hasText(text(R.string.capture))))
        tap(R.string.keep_looking)
        waitForText(text(R.string.capture))
        assertTrue(has(hasText(text(R.string.hint))))
    }

    @Test
    fun theHintButtonLabelStaysOnOneLine() {
        startHuntList()
        tap(R.string.start_looking)
        waitFor(hasText(text(R.string.hint)) and hasClickAction() and isEnabled(), timeoutMs = NETWORK_TIMEOUT)
        // The unmerged text node, since the merged button is wide however its label wraps.
        val label = nodes(hasText(text(R.string.hint)), merged = false).first().boundsInRoot
        assertTrue("the Hint label wraps: ${label.width} x ${label.height}", label.width > label.height)
    }

    @Test
    fun nextHintStepsThroughEveryHintAndTheLastHasNoNextButton() {
        openFirstHint()
        var at = 1
        while (has(hasText(text(R.string.hint_next, at + 1)))) {
            tap(text(R.string.hint_next, at + 1))
            at++
            waitFor(hasText(headerAt(at), substring = true))
            assertTrue("earlier hints stay above", has(hasText(text(R.string.hint_earlier, 1, ""), substring = true)))
        }
        assertFalse(has(hasText(text(R.string.hint_next, at + 1))))
        assertTrue(has(hasText(text(R.string.keep_looking))))
    }
}
