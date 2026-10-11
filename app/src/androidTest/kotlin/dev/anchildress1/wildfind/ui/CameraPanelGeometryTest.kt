package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.game.CameraState
import dev.anchildress1.wildfind.core.hunt.Hint
import dev.anchildress1.wildfind.ui.theme.WildFindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Capture state changes keep the measured camera reservation stable across text sizes and layouts. */
class CameraPanelGeometryTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readyCheckingAndRetryKeepPanelGeometryAndOnlyExposeTheCurrentAction() {
        val camera = mutableStateOf(CameraState())
        val width = mutableStateOf(411)
        val scale = mutableStateOf(2f)
        val hints = mutableStateOf(true)
        var height = 0
        var action = 0
        var captures = 0
        compose.setContent {
            WildFindTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    Box(Modifier.requiredSize(width.value.dp, 600.dp)) {
                        CameraPanels(
                            CameraTarget(
                                1,
                                "Plant",
                                null,
                                1,
                                3,
                                hints = if (hints.value) listOf(Hint("Look at the leaves.")) else emptyList(),
                            ),
                            camera.value, camera.value.ready, false, { captures++ }, {}, {}, {}, { action = it },
                            Modifier.onSizeChanged { height = it.height },
                        )
                    }
                }
            }
        }
        listOf(Triple(411, 2f, true), Triple(320, 2f, true), Triple(411, 1f, true), Triple(411, 2f, false))
            .forEach { (size, fontScale, hasHints) ->
                compose.runOnIdle {
                    width.value = size
                    scale.value = fontScale
                    hints.value = hasHints
                    camera.value = CameraState()
                }
                compose.waitForIdle()
                val reserved = compose.runOnIdle { height }
                assertTrue(reserved > 0)
                assertCurrentAction(CameraState())

                listOf(CameraState(ready = true), CameraState(ready = true, checking = true), CameraState(ready = true))
                    .forEach { state ->
                        compose.runOnIdle { camera.value = state }
                        compose.waitForIdle()
                        compose.runOnIdle {
                            assertEquals("width=$size scale=$fontScale hints=$hasHints", reserved, height)
                            assertEquals(reserved, action)
                        }
                        assertCurrentAction(state)
                    }
                compose.onNodeWithText(context.getString(R.string.capture)).performClick()
            }
        compose.runOnIdle { assertEquals(4, captures) }
    }

    @Test
    fun aRestoredOpenSheetReservesActionsWithoutExposingThemAndKeepsTheReservationOnClose() {
        val sheet = mutableStateOf(true)
        var shown = 0
        var action = 0
        compose.setContent {
            WildFindTheme {
                val density = LocalDensity.current
                // 200% text makes the sheet taller than the action panel, so the two heights can't be confused.
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.requiredSize(411.dp, 800.dp)) {
                        CameraPanels(
                            CameraTarget(
                                1,
                                "Plant",
                                null,
                                1,
                                3,
                                hints = List(3) { Hint("Look closely at the leaves and the stem, hint ${it + 1}.") },
                                hintsShown = 3,
                            ),
                            CameraState(ready = true), true, sheet.value, {}, {}, {}, { sheet.value = it },
                            { action = it },
                            Modifier.onSizeChanged { shown = it.height },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val open = compose.runOnIdle { shown }
        assertTrue(open > 0)
        val reserved = compose.runOnIdle { action }
        assertTrue(reserved > 0)
        assertTrue("sheet=$open action=$reserved", open > reserved)
        listOf(R.string.capture, R.string.hint, R.string.skip_target).forEach { label ->
            compose.onNodeWithText(context.getString(label)).assertDoesNotExist()
        }

        compose.onNodeWithText(context.getString(R.string.keep_looking)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(reserved, action)
            assertEquals(reserved, shown)
        }
    }

    private fun assertCurrentAction(state: CameraState) {
        val current = when {
            state.checking -> R.string.checking
            state.ready -> R.string.capture
            else -> R.string.getting_ready
        }
        listOf(R.string.getting_ready, R.string.capture, R.string.checking).forEach { label ->
            val node = compose.onNodeWithText(context.getString(label))
            when {
                label != current -> node.assertDoesNotExist()
                state.checking || !state.ready -> node.assertIsNotEnabled()
                else -> node.assertIsEnabled()
            }
        }
    }
}
