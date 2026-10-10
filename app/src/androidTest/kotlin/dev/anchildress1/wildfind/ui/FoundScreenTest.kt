package dev.anchildress1.wildfind.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.inference.BundledAssets
import dev.anchildress1.wildfind.ui.theme.WildFindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** S59: Found shows the plant's shipped description under its name, as a node TalkBack reads. */
@RunWith(AndroidJUnit4::class)
class FoundScreenTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    private val bundled = BundledAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)

    // A real find needs a real plant, so this renders the screen with a shipped row instead of driving a capture.
    @Test
    fun foundShowsTheDescriptionUnderTheName() {
        val row = bundled.speciesLabels(bundled.speciesTable()).first { it.playable && it.description != null }
        val description = row.description!!
        compose.setContent {
            WildFindTheme {
                FoundScreen(FoundInfo(row.scientific, 1, 3, "maple", false, description), null, {}, {})
            }
        }

        val name = compose.onAllNodes(hasText(row.scientific)).fetchSemanticsNodes().single().boundsInRoot
        val line = compose.onAllNodes(hasText(description)).fetchSemanticsNodes()
        assertEquals(1, line.size)
        assertTrue("the description is not under the name", line.single().boundsInRoot.top >= name.bottom)
    }
}
