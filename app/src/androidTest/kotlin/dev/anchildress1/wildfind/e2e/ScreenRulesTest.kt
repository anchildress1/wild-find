package dev.anchildress1.wildfind.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.ui.BANNED_COPY
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** R1 copy and the accessibility bar, checked on every screen a first hunt passes through. */
@RunWith(AndroidJUnit4::class)
class ScreenRulesTest : E2eTest() {
    @Test
    fun noVisitedScreenSaysAPlantIsSafeHarmlessNotPoisonousOrOkayToTouch() {
        val said = mutableMapOf<String, List<String>>()
        tour { screen ->
            val words = nodes(SemanticsMatcher("any") { true }, merged = false)
                .flatMap { it.texts() + it.contentDescriptions() }
            said[screen] = words.filter { BANNED_COPY.containsMatchIn(it) }
        }
        assertEquals(emptyMap<String, List<String>>(), said.filterValues { it.isNotEmpty() })
    }

    @Test
    fun everyButtonOnEveryVisitedScreenIsAtLeast48dpAndLabelled() {
        val min = context.resources.displayMetrics.density * MIN_TOUCH_DP
        val broken = mutableListOf<String>()
        tour { screen ->
            nodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).forEach { node ->
                val label = (node.texts() + node.contentDescriptions()).joinToString(" ").trim()
                val bounds = node.touchBoundsInRoot
                if (label.isEmpty()) broken += "$screen: a button with no label at $bounds"
                // Touch bounds are clipped to the screen, so a button scrolled out of view reads 0x0; its laid-out
                // size still counts. Touch bounds can be the larger one, where a small icon is padded to 48 dp.
                val width = maxOf(bounds.width, node.size.width.toFloat())
                val height = maxOf(bounds.height, node.size.height.toFloat())
                // A hair under 48 dp is rounding between dp and pixels, not a small target.
                if (width < min - 1 || height < min - 1) {
                    broken += "$screen: \"$label\" is ${width / min * MIN_TOUCH_DP}x${height / min * MIN_TOUCH_DP} dp"
                }
            }
        }
        assertEquals(emptyList<String>(), broken)
    }

    // Opener, area choice, map, grass practice, grass camera, hunt list, target camera, grown-ups, replayed opener,
    // hunt complete, and start; [check] runs once each screen has settled on its key line.
    private fun tour(check: (String) -> Unit) {
        fun at(screen: String, line: String) {
            waitForText(line)
            check(screen)
        }
        at("opener", text(R.string.rule_leave))
        tap(R.string.opener_go)
        at("area choice", text(R.string.region_title))
        tap(R.string.region_pick_map)
        pickOnMap(WEST_GEORGIA_LAT, WEST_GEORGIA_LNG)
        at("map", text(R.string.map_hunt_here))
        tap(R.string.map_hunt_here)
        awaitHunt(text(R.string.tutorial_title))
        at("grass practice", text(R.string.tutorial_title))
        tap(R.string.try_it)
        at("grass camera", text(R.string.skip_practice))
        tap(R.string.skip_practice)
        at("hunt list", text(R.string.start_looking))
        tap(R.string.start_looking)
        at("target camera", text(R.string.skip_target))
        tap(R.string.back_to_hunt)
        tap(R.string.grown_ups)
        at("grown-ups", text(R.string.privacy))
        tap(R.string.replay_opener)
        at("replayed opener", text(R.string.opener_done))
        tap(R.string.opener_done)
        tap(R.string.back)
        tap(R.string.finish_hunt)
        at("hunt complete", text(R.string.complete_title))
        tap(R.string.home)
        at("start", text(R.string.start_button))
    }

    private companion object {
        const val MIN_TOUCH_DP = 48f
    }
}
