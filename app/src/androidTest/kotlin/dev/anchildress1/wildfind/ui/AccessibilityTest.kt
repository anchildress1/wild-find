package dev.anchildress1.wildfind.ui

import android.Manifest
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.game.AreaCache
import dev.anchildress1.wildfind.core.game.CameraState
import dev.anchildress1.wildfind.core.hunt.Hint
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.WildFindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

/** Regression coverage for motion preference changes, heading navigation, and large-text hint access. */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun themeStartsWithReducedMotionWhenAnimationsAreAlreadyDisabled() = withAnimationScale(0f) {
        compose.setContent { WildFindTheme { Text("reduced=${LocalReducedMotion.current}") } }
        compose.onNodeWithText("reduced=true").assertIsDisplayed()
    }

    @Test
    fun themeRespondsToAnimationSettingsWithoutRecreation() = withAnimationScale(1f) {
        compose.setContent { WildFindTheme { Text("reduced=${LocalReducedMotion.current}") } }
        compose.onNodeWithText("reduced=false").assertIsDisplayed()
        listOf(0f, 1f, 0f).forEach { scale ->
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodesWithText("reduced=${scale == 0f}").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun switchingToReducedMotionStopsBriarAndRestoresTheFirstFrame() {
        val reduced = mutableStateOf(true)
        compose.setContent {
            WildFindTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    Briar(null, "Briar", Modifier.testTag("briar"), figure = 56.dp)
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { pixels().distinct().size > 2 }
        val first = pixels()
        compose.runOnIdle { reduced.value = false }
        compose.waitUntil(TIMEOUT_MS) { !pixels().contentEquals(first) }
        compose.runOnIdle { reduced.value = true }
        compose.waitUntil(TIMEOUT_MS) {
            val shown = pixels()
            assertTrue("Briar went blank while resetting reduced motion", shown.distinct().size > 2)
            shown.contentEquals(first)
        }
        repeat(3) {
            Thread.sleep(FRAME_WAIT_MS)
            org.junit.Assert.assertArrayEquals(first, pixels())
        }
    }

    @Test
    fun enablingReducedMotionFinishesPendingStarAndEntryAnimations() = withAnimationScale(1f) {
        val reduced = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WildFindTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    Box(Modifier.size(100.dp)) {
                        Box(Modifier.size(48.dp).rise(index = 5).testTag("entry"))
                        Box(Modifier.size(48.dp).pop(index = 5).testTag("star"))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val entry = compose.onNodeWithTag("entry")
        assertTrue(entry.fetchSemanticsNode().boundsInRoot.top > 0f)
        compose.runOnIdle { reduced.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0f, entry.fetchSemanticsNode().boundsInRoot.top, 0f)
        assertTrue(compose.onNodeWithTag("star").fetchSemanticsNode().boundsInRoot.width > 0f)
    }

    @Test
    fun grownUpsSectionsExposeHeadingNavigation() {
        compose.setContent { WildFindTheme { GrownUpsScreen(null, AreaCache.Idle, {}, {}, {}, {}) } }
        listOf(
            R.string.grown_ups,
            R.string.privacy,
            R.string.inat_title,
            R.string.cache_title,
            R.string.made_with,
        ).forEach { resource ->
            compose.onNodeWithText(context.getString(resource))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun longHintsAndBothActionsAreReachableOnAShortScreenAtDoubleFontSize() {
        val hints = List(3) { Hint("Look for leaves beside the trail. ".repeat(5) + "Hint ${it + 1}.") }
        var next = 0
        var close = 0
        compose.setContent {
            WildFindTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.size(width = 320.dp, height = 320.dp)) {
                        HintPanel(hints, 2, { next++ }, { close++ })
                    }
                }
            }
        }
        compose.onNodeWithText(hints[1].text).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hint_next, 3))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.keep_looking))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, next)
            assertEquals(1, close)
        }
    }

    @Test
    fun hazardWarningAndDismissalAreReachableAtDoubleFontSizeOnAShortScreen() {
        var dismissed = 0
        val camera = mutableStateOf(CameraState(ready = true))
        compose.setContent {
            WildFindTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.requiredSize(width = 320.dp, height = 600.dp)) {
                        CameraScreen(
                            CameraTarget(null, "Grass", null, 0, 0),
                            camera.value,
                            null, Executor { it.run() }, {}, {}, {}, {}, { dismissed++ },
                        )
                    }
                }
            }
        }
        val reserved = compose.onNodeWithTag("camera_actions").fetchSemanticsNode().size.height
        compose.runOnIdle {
            camera.value = camera.value.copy(
                cue = CaptureCue.HAZARD,
                hazardLine = "Tiny bits on the leaves and stems can prick and make your skin sting.",
            )
        }
        compose.waitForIdle()
        assertEquals(reserved, compose.onNodeWithTag("camera_actions").fetchSemanticsNode().size.height)
        val scrollable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().isNotEmpty()
        val detail = compose.onNodeWithText(context.getString(R.string.hazard_detail))
        val dismiss = compose.onNodeWithText(context.getString(R.string.hazard_dismiss))
        if (scrollable) detail.performScrollTo()
        detail.assertIsDisplayed()
        if (scrollable) dismiss.performScrollTo()
        dismiss.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(context.getString(R.string.capture)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    private fun withAnimationScale(scale: Float, test: () -> Unit) {
        val resolver = context.contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, key)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Global.putFloat(resolver, key, scale)
            test()
        } finally {
            Settings.Global.putString(resolver, key, original)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun pixels(): IntArray {
        val bitmap = compose.onNodeWithTag("briar").captureToImage().asAndroidBitmap()
            .copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val FRAME_WAIT_MS = 200L
    }
}
