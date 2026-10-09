package dev.anchildress1.wildfind.e2e

import android.Manifest
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.anchildress1.wildfind.Graph
import dev.anchildress1.wildfind.MainActivity
import dev.anchildress1.wildfind.Models
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.WildFindApp
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.region.RegionKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.io.File
import kotlin.math.abs

/**
 * Drives the real [MainActivity] on the phone from a clean store: no flags, no hunt, no cached iNat pull. The gate
 * harness's runs live in external files and are never touched.
 */
abstract class E2eTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION)

    @get:Rule(order = 1)
    val compose: ComposeTestRule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val testName = TestName()

    /**
     * The area this test hunts in. Each test name maps to one of [AREAS], so a run spans several real iNat pulls
     * instead of proving the hunt in one place only, and a failing test reruns in the same place.
     */
    protected val area: Area get() = AREAS[Math.floorMod(testName.methodName.hashCode(), AREAS.size)]

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext!!
    protected val graph: Graph get() = (context.applicationContext as WildFindApp).graph
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun cleanLaunch() {
        clearStore()
        launch()
    }

    @After
    fun closeApp() {
        scenario?.close()
        scenario = null
    }

    /** Closes the activity and opens a new one, so a fresh view model reads only what the store kept. */
    protected fun relaunch() {
        closeApp()
        launch()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun clearStore() {
        val dir = context.noBackupFilesDir
        listOf("flags.json", "hunt.json").forEach { File(dir, it).delete() }
        File(dir, "inat").deleteRecursively()
    }

    protected fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    protected fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    /** Waits up to [timeoutMs] of real time for a node matching [matcher], failing with [why]. */
    protected fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = UI_TIMEOUT, why: String? = null) {
        waitUntil(timeoutMs, why ?: "nothing on screen matched ${matcher.description}") { has(matcher) }
    }

    protected fun waitForText(text: String, timeoutMs: Long = UI_TIMEOUT) = waitFor(hasText(text), timeoutMs)

    protected fun waitUntil(timeoutMs: Long, why: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("after ${timeoutMs}ms: $why")
            Thread.sleep(POLL_MS)
        }
    }

    protected fun has(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    protected fun nodes(matcher: SemanticsMatcher, merged: Boolean = true): List<SemanticsNode> =
        compose.onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes(atLeastOneRootRequired = false)

    /** Taps the button that shows [label] as its text or its content description. */
    protected fun tap(label: String) {
        val button = (hasText(label) or hasContentDescription(label)) and hasClickAction()
        waitFor(button, why = "no button labelled \"$label\"")
        val node = compose.onAllNodes(button)[0]
        // A tap lands where the node sits, so one scrolled out of view comes into view first.
        if (has(button and hasAnyAncestor(hasScrollAction()))) node.performScrollTo()
        node.performClick()
    }

    protected fun tap(@StringRes id: Int) = tap(text(id))

    /** The phone's own Back key, not the in-app arrow. */
    protected fun pressBack() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    protected fun models(): Models = runBlocking { graph.models.await() }

    /** The hunt as the store saved it, once the save lands. */
    protected fun savedHunt(): ActiveHunt {
        val version = models().tableVersion
        var hunt: ActiveHunt? = null
        waitUntil(UI_TIMEOUT, "the hunt was never saved") { graph.store.hunt(version).also { hunt = it } != null }
        return hunt!!
    }

    // ---- Shared journeys ----

    /** First launch: Let's go on the safety opener. */
    protected fun passOpener() {
        waitForText(text(R.string.rule_leave))
        tap(R.string.opener_go)
        waitForText(text(R.string.region_title))
    }

    /**
     * The map picker, zoomed in until the crosshairs sit on [lat], [lng] (whole degrees, negative = S or W). With
     * location allowed the map may open on the phone's own area, so the arrows steer from wherever it lands.
     */
    protected fun pickOnMap(lat: Int, lng: Int) {
        waitForText(text(R.string.map_title))
        repeat(ZOOM_TAPS) { tap(R.string.map_zoom_in) }
        waitForText(text(R.string.map_hunt_here))
        repeat(MAX_STEPS) {
            val (atLat, atLng) = crosshairs()
            when {
                atLat < lat -> tap(R.string.map_north)
                atLat > lat -> tap(R.string.map_south)
                atLng < lng -> tap(R.string.map_east)
                atLng > lng -> tap(R.string.map_west)
                else -> return
            }
        }
        throw AssertionError("the arrow pad never reached $lat, $lng; at ${crosshairs()}")
    }

    /** The whole degrees under the crosshairs, read from the map's TalkBack label. */
    protected fun crosshairs(): Pair<Int, Int> {
        val label = context.resources.getString(R.string.map_area_label).substringBefore("%s")
        val description = nodes(hasContentDescription(label, substring = true))
            .flatMap { it.contentDescriptions() }.first { it.startsWith(label) }
        val match = DEGREES.find(description) ?: throw AssertionError("no degrees in \"$description\"")
        val lat = match.groupValues[1].toInt().let { if (match.groupValues[2] == "S") -it else it }
        val lng = match.groupValues[3].toInt().let { if (match.groupValues[4] == "W") -it else it }
        return lat to lng
    }

    /**
     * From a first launch whose opener is done and whose area is [area] to the grass practice. The opener and the map
     * have their own tests; walking the arrow pad to Tbilisi would take over a hundred taps.
     */
    protected fun startFirstHunt() {
        closeApp()
        graph.store.save(AppFlags(openerSeen = true, region = area.region))
        launch()
        tap(R.string.start_button)
        awaitHunt(text(R.string.tutorial_title))
    }

    /** Waits out the iNat pull for [ready], failing plainly when the phone has no signal or the area is short. */
    protected fun awaitHunt(ready: String) {
        waitUntil(NETWORK_TIMEOUT, "no hunt loaded; is the phone online?") {
            check(!has(hasText(text(R.string.needs_signal)))) {
                "iNat didn't answer and nothing is cached: the phone needs signal for this test"
            }
            check(!has(hasText(text(R.string.not_enough)))) { "${area.name} came back short of 3 genera" }
            has(hasText(ready))
        }
    }

    /** From a fresh install to the hunt list, skipping the grass practice. */
    protected fun startHuntList() {
        startFirstHunt()
        tap(R.string.try_it)
        tap(R.string.skip_practice)
        waitForText(plural(R.plurals.hunt_title, TARGETS, TARGETS))
    }

    /** The hunt list's stops in trail order, as TalkBack reads them: "1, name, a tree, not found yet". */
    protected fun stops(): List<String> {
        val states = stopStates()
        val stop = SemanticsMatcher("a hunt stop") { node ->
            node.contentDescriptions().any { d -> d.first().isDigit() && states.any { it in d } }
        }
        return nodes(stop).flatMap { it.contentDescriptions() }.sortedBy { it.substringBefore(",").toInt() }
    }

    // ", not found yet" and ", found", the endings of a stop's TalkBack label.
    private fun stopStates() =
        listOf(R.string.stop_plain_open, R.string.stop_plain_found).map { text(it, 0, "").substringAfter("0, ") }

    /** A stop's plant name, cut from its TalkBack label. */
    protected fun stopName(description: String): String {
        val types = PlantType.entries.map { ", " + text(typeLabel(it)) + ", " }
        val states = stopStates()
        val rest = description.substringAfter(", ")
        return rest.substring(0, (types + states).mapNotNull { end -> rest.indexOf(end).takeIf { it > 0 } }.min())
    }

    protected companion object {
        const val UI_TIMEOUT = 15_000L
        const val NETWORK_TIMEOUT = 90_000L
        const val POLL_MS = 50L
        const val TARGETS = 3
        const val WEST_GEORGIA_LAT = 34
        const val WEST_GEORGIA_LNG = -85

        /** The places `docs/results/day-2/playable_species.log` measured as playable, near and far, busy and sparse. */
        val AREAS = listOf(
            Area("West Georgia", RegionKey(WEST_GEORGIA_LAT, WEST_GEORGIA_LNG)),
            Area("Atlanta", RegionKey(34, -84)),
            Area("Tbilisi", RegionKey(42, 45)),
            Area("Borjomi", RegionKey(42, 43)),
        )

        // 360° across halves on each tap: 5 taps reach 11.25°, inside the 12° "Hunt here" bar.
        const val ZOOM_TAPS = 5
        const val MAX_STEPS = 200
        val DEGREES = Regex("""(\d+)°([NS]), (\d+)°([EW])""")
    }
}

/** Every content description on this node. */
fun SemanticsNode.contentDescriptions(): List<String> =
    config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()

/** Every text on this node. */
fun SemanticsNode.texts(): List<String> =
    config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text }

@StringRes
private fun typeLabel(type: PlantType): Int = when (type) {
    PlantType.TREE -> R.string.type_tree
    PlantType.SHRUB -> R.string.type_shrub
    PlantType.VINE -> R.string.type_vine
    PlantType.HERB -> R.string.type_herb
    PlantType.GRASS -> R.string.type_grass
    PlantType.FERN -> R.string.type_fern
    PlantType.MOSS -> R.string.type_moss
    PlantType.CONIFER -> R.string.type_conifer
}

/** A hunting area a test plays in, named for failure messages. */
data class Area(val name: String, val region: RegionKey) {
    /** The whole degrees the app shows for this area, as in "34°N, 85°W". */
    val degrees: String
        get() = "${abs(region.lat)}°${if (region.lat < 0) "S" else "N"}, " +
            "${abs(region.lng)}°${if (region.lng < 0) "W" else "E"}"
}
