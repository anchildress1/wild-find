package dev.anchildress1.wildfind.core.game

import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.hunt.Hint
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.CaptureCue

/** Where the kid is; each screen change animates. */
sealed interface Screen {
    /** Reading flags at launch. */
    data object Starting : Screen

    /**
     * The safety opener (R1).
     *
     * @property back where Done returns on a replay; null on first launch
     */
    data class Opener(val back: Screen?) : Screen

    /**
     * The hunting-area choice: the rough location, or the map (R2, R7).
     *
     * @property back where Back returns when the area was already set
     */
    data class Region(val back: Screen?) : Screen

    /**
     * The built-in map picker.
     *
     * @property back where Back returns
     */
    data class Map(val back: Screen?) : Screen

    /** Waiting for the models or the iNat pull. */
    data object Loading : Screen

    /** No answer from iNat and no cache for this place. */
    data object NeedsSignal : Screen

    /** The coverage message. */
    data object NotEnough : Screen

    /** No hunt yet: start one. */
    data object Start : Screen

    /** The grass tutorial's intro (R3). */
    data object Tutorial : Screen

    /** The hunt list. */
    data object Hunt : Screen

    /**
     * The live camera.
     *
     * @property row the target's table row, or null for the grass tutorial
     */
    data class Camera(val row: Int?) : Screen

    /**
     * A find.
     *
     * @property row the target found, or null for the grass tutorial
     */
    data class Found(val row: Int?) : Screen

    /** Stars and Hunt Again / Home (R15). */
    data object Complete : Screen

    /**
     * Privacy, area, credits, and the opener replay.
     *
     * @property from where Back returns
     */
    data class GrownUps(val from: Screen) : Screen
}

/**
 * One target as the kid sees it.
 *
 * @property row the species-table row
 * @property name the iNat common name
 * @property type its plant type, or null for name only
 * @property description what to look for, from USDA traits, or null to show the type alone
 * @property found already found this hunt
 * @property canSkip a skip would swap in another plant; false once the queue holds none that fits
 * @property hints the build's ranked hints for this plant, season ones included; the screen orders them by month
 */
data class Stop(
    val row: Int,
    val name: String,
    val type: PlantType?,
    val found: Boolean,
    val description: String? = null,
    val canSkip: Boolean = false,
    val hints: List<Hint> = emptyList(),
)

/**
 * The camera screen between taps.
 *
 * @property ready the models are loaded, so Capture works
 * @property checking a capture's frames are being verified
 * @property matched matching frames so far in this capture, for the ring
 * @property cue the last capture's result; stays until the next one
 * @property hazardLine the warning hazard's own kid line under a [CaptureCue.HAZARD] cue, or null for the generic card
 */
data class CameraState(
    val ready: Boolean = false,
    val checking: Boolean = false,
    val matched: Int = 0,
    val cue: CaptureCue? = null,
    val hazardLine: String? = null,
)

/** The grown-ups page's Cache my area button. */
sealed interface AreaCache {
    /** Nothing running or reported. */
    data object Idle : AreaCache

    /**
     * Pulling month [done] + 1 of [total].
     *
     * @property done months saved so far
     * @property total months to save
     */
    data class Running(val done: Int, val total: Int) : AreaCache

    /** Every month is saved on the phone. */
    data object Done : AreaCache

    /**
     * iNat or the signal gave out.
     *
     * @property done months that did get saved
     * @property total months asked for
     */
    data class Stopped(val done: Int, val total: Int) : AreaCache
}

/**
 * Everything the UI renders.
 *
 * @property screen the current screen
 * @property region the hunting area, null until picked
 * @property regionLabel the area as the kid reads it, `34°N, 85°W · Georgia`, once the offline names load
 * @property stops this hunt's targets in pick order
 * @property offline the hunt's list came from the cache because iNat didn't answer
 * @property locating waiting for the rough location
 * @property locationFailed the location was denied, so it is never asked again and only the map remains
 * @property mapFocus where the map's Locate button found the rough location
 * @property camera the camera screen's state
 * @property crop the last find's reticle crop, in memory only
 * @property areaCache the Cache my area button's progress
 * @property hintsShown how many hints the kid has opened per target row this hunt, so the Hint button shows the next
 */
data class GameState(
    val screen: Screen = Screen.Starting,
    val region: RegionKey? = null,
    val regionLabel: String? = null,
    val stops: List<Stop> = emptyList(),
    val offline: Boolean = false,
    val locating: Boolean = false,
    val locationFailed: Boolean = false,
    val mapFocus: MapFocus? = null,
    val camera: CameraState = CameraState(),
    val crop: Pixels? = null,
    val hintsShown: Map<Int, Int> = emptyMap(),
    val areaCache: AreaCache = AreaCache.Idle,
) {
    /** One star per find. */
    val stars: Int get() = stops.count { it.found }

    /** The stop at [row]. */
    fun stop(row: Int): Stop? = stops.firstOrNull { it.row == row }
}

/**
 * A Locate result for the map to center on.
 *
 * @property region the rough location's whole-degree region
 * @property serial bumps on every Locate, so finding the same area again still recenters
 * @property opening found on its own as the map opened, so it yields to a kid who already started moving the map
 */
data class MapFocus(val region: RegionKey, val serial: Int, val opening: Boolean = false)

/** Anything the game rules react to: what the kid did, or what a [Command] brought back. */
sealed interface GameInput

/** What the kid did. */
sealed interface GameEvent : GameInput {
    /** Let's go, or Done on a replay. */
    data object OpenerDone : GameEvent

    /**
     * The coarse-location permission answer.
     *
     * @property granted the kid or grown-up allowed it
     */
    data class LocationAnswer(val granted: Boolean) : GameEvent

    /**
     * A manual area pick.
     *
     * @property region the picked area
     */
    data class PickRegion(val region: RegionKey) : GameEvent

    /** "Pick on a map". */
    data object OpenMap : GameEvent

    /** Back to the area pick from a coverage message. */
    data object ChangeRegion : GameEvent

    /** Retry the pull, or start a hunt from Start. */
    data object LoadHunt : GameEvent

    /** Open the camera on [row], or the grass tutorial when null. */
    data class OpenCamera(val row: Int?) : GameEvent

    /** Capture tapped. */
    data object Capture : GameEvent

    /** The hazard card's button: the warning clears back to the live camera. */
    data object DismissHazard : GameEvent

    /** Hint tapped on the camera for [row]: opens the next hint it has not shown yet, or the last when all are open. */
    data class RevealHint(val row: Int) : GameEvent

    /** Swap the camera's target for the next species in the hunt's queue, or move past the grass tutorial. */
    data object Skip : GameEvent

    /** After a find, the next target or the stars. */
    data object Next : GameEvent

    /** Back to the hunt list. */
    data object ToHunt : GameEvent

    /** End the hunt early. */
    data object FinishHunt : GameEvent

    /** A fresh hunt in the same area. */
    data object HuntAgain : GameEvent

    /** Forget the hunt. */
    data object Home : GameEvent

    /** Open the grown-ups page. */
    data object OpenGrownUps : GameEvent

    /** Grown-ups: save every month of the hunting area on the phone for play without signal. */
    data object CacheArea : GameEvent

    /** Grown-ups: change the hunting area on the map. */
    data object EditRegion : GameEvent

    /** Grown-ups: replay the opener. */
    data object ReplayOpener : GameEvent

    /** System back. */
    data object Back : GameEvent
}

/** One-shot effects. */
sealed interface GameEffect {
    /** CONFIRM haptic on a find. */
    data object Confirm : GameEffect

    /** REJECT haptic, once per capture that flags a hazard. */
    data object Reject : GameEffect
}
