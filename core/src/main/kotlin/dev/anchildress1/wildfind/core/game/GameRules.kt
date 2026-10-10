package dev.anchildress1.wildfind.core.game

import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.AreaCacher
import dev.anchildress1.wildfind.core.hunt.HINTS_PER_TARGET
import dev.anchildress1.wildfind.core.hunt.HuntPick
import dev.anchildress1.wildfind.core.hunt.LocalListResult
import dev.anchildress1.wildfind.core.hunt.SpeciesRow
import dev.anchildress1.wildfind.core.map.Places
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.core.verify.FrameResult
import dev.anchildress1.wildfind.core.verify.PlantGate
import dev.anchildress1.wildfind.core.verify.Verdict
import kotlin.random.Random

/**
 * Everything the rules read and write between inputs.
 *
 * @property ui what the UI renders
 * @property flags the saved app flags
 * @property hunt the current hunt, null before one starts
 * @property rows the species table's rows, null until the models load
 * @property session bumped on every capture and whenever the camera opens or leaves
 */
data class Game(
    val ui: GameState = GameState(),
    val flags: AppFlags = AppFlags(),
    val hunt: ActiveHunt? = null,
    val rows: List<SpeciesRow>? = null,
    val session: Int = 0,
)

/**
 * One input's result.
 *
 * @property game the game after the input
 * @property commands the side effects to run, in order
 */
data class Step(val game: Game, val commands: List<Command>)

/** What a [Command] brought back. */
sealed interface Outcome : GameInput {
    /**
     * The saved flags, read at launch.
     *
     * @property flags what the store held
     */
    data class FlagsRead(val flags: AppFlags) : Outcome

    /**
     * The models loaded.
     *
     * @property rows the species table's rows
     */
    data class ModelsReady(val rows: List<SpeciesRow>) : Outcome

    /**
     * The offline place names, for [Command.Relabel].
     *
     * @property places the names
     */
    data class PlacesRead(val places: Places) : Outcome

    /**
     * The saved hunt, for [Command.ReadHunt].
     *
     * @property hunt the hunt that survived, or null
     */
    data class SavedHunt(val hunt: ActiveHunt?) : Outcome

    /**
     * A [Command.Pull] finished.
     *
     * @property region the area the pull was for
     * @property result the local list, or why there is none
     * @property offline iNat didn't answer, so the list came from the cache
     */
    data class Pulled(val region: RegionKey, val result: LocalListResult, val offline: Boolean) : Outcome

    /**
     * Months saved by a [Command.CacheArea], after each month and once more when it ends; ignored when the hunting
     * area has changed since the run started.
     *
     * @property region the area the run was caching
     * @property done months saved so far
     * @property finished the run ended, with all [AreaCacher.MONTHS] saved or stopped short
     */
    data class AreaReport(val region: RegionKey, val done: Int, val finished: Boolean) : Outcome

    /**
     * A [Command.Locate] fix.
     *
     * @property from the screen the kid answered on
     * @property region the rough location, or null without a fix
     */
    data class Located(val from: Screen, val region: RegionKey?) : Outcome

    /**
     * A [Command.LocateMap] fix.
     *
     * @property map the map screen that asked
     * @property region the rough location
     */
    data class MapLocated(val map: Screen.Map, val region: RegionKey) : Outcome

    /**
     * The verifier took a [Command.Capture].
     *
     * @property session the capture's session
     */
    data class CaptureStarted(val session: Int) : Outcome

    /**
     * One verified frame of a capture.
     *
     * @property camera the camera screen the capture started on
     * @property session the capture's session
     * @property result everything the frame produced
     * @property verdict its verdict under the capture's streak
     */
    data class Frame(val camera: Screen.Camera, val session: Int, val result: FrameResult, val verdict: Verdict) :
        Outcome
}

/** A side effect for the app to run. */
sealed interface Command {
    /**
     * Write the flags.
     *
     * @property flags the new flags
     */
    data class SaveFlags(val flags: AppFlags) : Command

    /**
     * Write the hunt.
     *
     * @property hunt the hunt to save
     */
    data class SaveHunt(val hunt: ActiveHunt) : Command

    /** Delete the saved hunt. */
    data object ClearHunt : Command

    /**
     * Pull every month for [region] into the cache, reporting [Outcome.AreaReport] after each month and when it ends.
     *
     * @property region the area to save
     */
    data class CacheArea(val region: RegionKey) : Command

    /** Stop the [CacheArea] run still going, because the hunting area it was saving is no longer the area. */
    data object CancelCacheArea : Command

    /**
     * Build the capture loop for a hunt, or drop it.
     *
     * @property local the hunt's local rows, or null to drop the loop
     */
    data class UseVerifier(val local: Set<Int>?) : Command

    /** Drop the running capture. */
    data object CancelCapture : Command

    /**
     * Start a capture; report [Outcome.CaptureStarted] if the verifier takes it, then each [Outcome.Frame].
     *
     * @property camera the camera screen
     * @property session the capture's session
     * @property hunt the hunt whose goal it verifies
     */
    data class Capture(val camera: Screen.Camera, val session: Int, val hunt: ActiveHunt) : Command

    /** Read the offline place names to label the area; report [Outcome.PlacesRead]. */
    data object Relabel : Command

    /** Read the saved hunt; report [Outcome.SavedHunt]. */
    data object ReadHunt : Command

    /**
     * Pull the local list, replacing any pull still running; report [Outcome.Pulled].
     *
     * @property region the area to pull
     */
    data class Pull(val region: RegionKey) : Command

    /**
     * Ask for the rough location; report [Outcome.Located].
     *
     * @property from the screen the kid answered on
     */
    data class Locate(val from: Screen) : Command

    /**
     * Ask for the rough location without prompting; report [Outcome.MapLocated] when there is a fix.
     *
     * @property map the map screen that asks
     */
    data class LocateMap(val map: Screen.Map) : Command

    /**
     * Play a haptic.
     *
     * @property effect which one
     */
    data class Haptic(val effect: GameEffect) : Command
}

/**
 * The game's rules: one hunt at a time, saved on every change so it survives process death.
 *
 * @param random the hunt pick's draw
 */
class GameRules(private val random: Random = Random.Default) {
    /** Applies [input] to [game]. */
    fun reduce(game: Game, input: GameInput): Step = Turn(game, random).apply {
        when (input) {
            is GameEvent -> on(input)
            is Outcome -> on(input)
        }
    }.step()
}

@Suppress("TooManyFunctions")
private class Turn(private var game: Game, private val random: Random) {
    private val commands = mutableListOf<Command>()
    private val ui get() = game.ui

    fun step() = Step(game, commands.toList())

    private fun update(change: (GameState) -> GameState) {
        game = game.copy(ui = change(game.ui))
    }

    @Suppress("CyclomaticComplexMethod")
    fun on(event: GameEvent) {
        val screen = ui.screen
        when (event) {
            GameEvent.OpenerDone -> openerDone(screen)
            is GameEvent.LocationAnswer -> located(event.granted)
            is GameEvent.PickRegion -> pick(event.region)
            GameEvent.OpenMap -> openMap(Screen.Map(back = screen))
            GameEvent.ChangeRegion -> show(Screen.Region(back = null))
            GameEvent.LoadHunt -> load()
            is GameEvent.OpenCamera -> openCamera(event.row)
            GameEvent.Capture -> capture(screen)
            GameEvent.DismissHazard -> dismissHazard()
            GameEvent.Next -> next(screen)
            GameEvent.Skip -> skip(screen)
            GameEvent.ToHunt -> toHunt()
            GameEvent.FinishHunt -> finish()
            is GameEvent.RevealHint -> revealHint(event.row)
            GameEvent.HuntAgain -> endHunt().also { load() }
            GameEvent.Home -> endHunt().also { show(Screen.Start) }
            GameEvent.OpenGrownUps -> show(Screen.GrownUps(from = screen)).also { idleAreaCache() }
            GameEvent.CacheArea -> cacheArea()
            GameEvent.EditRegion -> openMap(Screen.Map(back = screen))
            GameEvent.ReplayOpener -> show(Screen.Opener(back = screen))
            GameEvent.Back -> back(screen)
        }
    }

    fun on(outcome: Outcome) {
        when (outcome) {
            is Outcome.FlagsRead -> started(outcome.flags)

            is Outcome.ModelsReady -> ready(outcome.rows)

            is Outcome.PlacesRead -> update { ui ->
                ui.copy(regionLabel = ui.region?.let { Places.label(it, outcome.places.nameAt(it)) })
            }

            is Outcome.SavedHunt -> resumed(outcome.hunt)

            is Outcome.Pulled -> pulled(outcome)

            is Outcome.AreaReport -> if (outcome.region == game.flags.region) areaReported(outcome)

            is Outcome.Located -> fixed(outcome.from, outcome.region)

            is Outcome.MapLocated -> focusMap(outcome.map, outcome.region)

            is Outcome.CaptureStarted -> captureStarted(outcome.session)

            is Outcome.Frame -> captured(outcome)
        }
    }

    private fun show(screen: Screen) {
        if (ui.screen is Screen.Camera && screen != ui.screen) {
            commands += Command.CancelCapture
            game = game.copy(session = game.session + 1)
        }
        update { it.copy(screen = screen) }
    }

    private fun back(screen: Screen) {
        when (screen) {
            is Screen.Opener -> screen.back?.let(::show)
            is Screen.Region -> screen.back?.let(::show)
            is Screen.Map -> screen.back?.let(::show)
            is Screen.GrownUps -> show(screen.from)
            is Screen.Camera, is Screen.Found -> toHunt()
            Screen.Complete -> on(GameEvent.Home)
            else -> Unit
        }
    }

    private fun started(flags: AppFlags) {
        game = game.copy(flags = flags)
        update { it.copy(region = flags.region, locationFailed = flags.locationDenied) }
        commands += Command.Relabel
        if (flags.openerSeen) resume() else show(Screen.Opener(back = null))
    }

    private fun ready(rows: List<SpeciesRow>) {
        game = game.copy(rows = rows)
        update { it.copy(camera = it.camera.copy(ready = true)) }
    }

    // First launch, or after the area is set: the saved hunt if one survives, else a fresh start.
    private fun resume() {
        if (game.flags.region == null) return show(Screen.Region(back = null))
        show(Screen.Loading)
        commands += Command.ReadHunt
    }

    private fun resumed(saved: ActiveHunt?) {
        val rows = game.rows
        when {
            saved == null -> show(Screen.Start)

            // Without the table a valid hunt can't be told from a broken one, so read it again rather than delete it.
            rows == null -> commands += Command.ReadHunt

            !saved.validFor(game.flags.region, rows) -> {
                commands += Command.ClearHunt
                show(Screen.Start)
            }

            else -> {
                startHunt(saved)
                show(
                    when {
                        saved.progress.tutorialPending -> Screen.Tutorial
                        saved.progress.complete -> Screen.Complete
                        else -> Screen.Hunt
                    },
                )
            }
        }
    }

    private fun openerDone(screen: Screen) {
        val back = (screen as? Screen.Opener)?.back
        if (back != null) return show(back)
        save(game.flags.copy(openerSeen = true))
        resume()
    }

    // From the area choice, a found area starts the hunt and anything else opens the map; on the map, Locate only
    // recenters. A denial is final: neither button asks again.
    private fun located(granted: Boolean) {
        val screen = ui.screen
        if (!granted) {
            save(game.flags.copy(locationDenied = true))
            update { it.copy(locationFailed = true) }
            return fallback(screen)
        }
        update { it.copy(locating = true) }
        commands += Command.Locate(screen)
    }

    private fun fallback(screen: Screen) {
        if (screen is Screen.Region) show(Screen.Map(back = screen))
    }

    private fun fixed(screen: Screen, region: RegionKey?) {
        update { it.copy(locating = false) }
        // A fix that lands after the kid moved on (picked on the map, went back) must not start a hunt.
        if (ui.screen != screen) return
        when {
            region == null -> fallback(screen)
            screen is Screen.Map -> update { it.copy(mapFocus = MapFocus(region, (it.mapFocus?.serial ?: 0) + 1)) }
            else -> pick(region)
        }
    }

    // With location already allowed, the map opens on the rough location, zoomed in enough to pick; it never asks
    // here, so without permission (or a fix) it stays on the whole world.
    private fun openMap(map: Screen.Map) {
        show(map)
        commands += Command.LocateMap(map)
    }

    private fun focusMap(map: Screen.Map, region: RegionKey) {
        // Identity, not equality: a map reopened with the same back is a new map the kid may already be moving.
        if (ui.screen === map) {
            update { it.copy(mapFocus = MapFocus(region, (it.mapFocus?.serial ?: 0) + 1, opening = true)) }
        }
    }

    private fun pick(region: RegionKey) {
        val back = when (val screen = ui.screen) {
            is Screen.Region -> screen.back

            // Through the area choice, back to wherever that came from.
            is Screen.Map -> (screen.back as? Screen.Region)?.back ?: screen.back?.takeUnless { it is Screen.Region }

            else -> null
        }
        // Confirming the area already set changes nothing, hunt or not: no new pull, just back where the kid came from.
        if (region == game.flags.region && back != null) return show(back)
        save(game.flags.copy(region = region))
        update { it.copy(region = region) }
        commands += Command.Relabel
        endHunt()
        load()
    }

    private fun load() {
        val region = game.flags.region ?: return show(Screen.Region(back = null))
        show(Screen.Loading)
        commands += Command.Pull(region)
    }

    private fun pulled(pulled: Outcome.Pulled) {
        val local = (pulled.result as? LocalListResult.Ready)?.local
        val planned = local?.let {
            HuntPick(checkNotNull(game.rows), random).next(it, game.flags.tutorialDone, game.flags.foundSpecies)
        }
        when {
            pulled.result == LocalListResult.NeedsSignal -> show(Screen.NeedsSignal)

            local == null || planned == null || planned.targets.size < HuntPick.TARGETS -> show(Screen.NotEnough)

            else -> {
                val fresh = ActiveHunt.start(planned, local, pulled.region)
                startHunt(fresh)
                persist(fresh)
                update { it.copy(offline = pulled.offline) }
                show(if (fresh.progress.tutorialPending) Screen.Tutorial else Screen.Hunt)
            }
        }
    }

    // One run at a time; a finished report stays until the kid leaves and comes back to the grown-ups page.
    private fun cacheArea() {
        val region = game.flags.region ?: return
        if (game.ui.areaCache is AreaCache.Running) return
        update { it.copy(areaCache = AreaCache.Running(0, AreaCacher.MONTHS)) }
        commands += Command.CacheArea(region)
    }

    private fun areaReported(report: Outcome.AreaReport) = update {
        val total = AreaCacher.MONTHS
        it.copy(
            areaCache = when {
                !report.finished -> AreaCache.Running(report.done, total)
                report.done == total -> AreaCache.Done
                else -> AreaCache.Stopped(report.done, total)
            },
        )
    }

    private fun idleAreaCache() {
        if (game.ui.areaCache !is AreaCache.Running) update { it.copy(areaCache = AreaCache.Idle) }
    }

    // Opens the next hint for [row], capped at what the plant has and the hint limit.
    private fun revealHint(row: Int) {
        val available = minOf(HINTS_PER_TARGET, game.rows?.get(row)?.hints?.size ?: 0)
        update { ui -> ui.copy(hintsShown = ui.hintsShown + (row to minOf((ui.hintsShown[row] ?: 0) + 1, available))) }
    }

    private fun startHunt(active: ActiveHunt) {
        update { it.copy(hintsShown = emptyMap()) }
        game = game.copy(hunt = active)
        commands += Command.UseVerifier(active.local)
        publish(active)
    }

    private fun publish(active: ActiveHunt) {
        val rows = game.rows ?: return
        update { ui ->
            ui.copy(
                stops = active.progress.targets.map {
                    val species = rows[it.row]
                    Stop(
                        it.row,
                        it.common,
                        species.type,
                        it.row in active.progress.found,
                        species.description,
                        active.progress.canSkip(it.row) { row -> rows[row].genus },
                        species.hints,
                    )
                },
            )
        }
    }

    private fun openCamera(row: Int?) {
        if (row != null && game.hunt?.progress?.found?.contains(row) == true) return
        update { it.copy(camera = CameraState(ready = game.rows != null)) }
        show(Screen.Camera(row))
    }

    private fun capture(screen: Screen) {
        val camera = screen as? Screen.Camera ?: return
        val active = game.hunt
        if (active == null || game.rows == null) return
        commands += Command.Capture(camera, game.session + 1, active)
    }

    private fun dismissHazard() {
        if (ui.camera.cue != CaptureCue.HAZARD) return
        update { it.copy(camera = it.camera.copy(cue = null, hazardLine = null)) }
    }

    // A refused tap never reports, so it leaves the running session alone.
    private fun captureStarted(session: Int) {
        game = game.copy(session = session)
        update { it.copy(camera = it.camera.copy(checking = true, matched = 0)) }
    }

    private fun captured(frame: Outcome.Frame) {
        // A capture that finishes after its screen left, or after a newer capture began, changes nothing.
        if (frame.session != game.session || ui.screen != frame.camera) return
        val verdict = frame.verdict
        if (verdict is Verdict.Matching) {
            return update { it.copy(camera = it.camera.copy(matched = verdict.frames)) }
        }
        val cue = CaptureCue.of(verdict, PlantGate.isPlant(frame.result.fullShare))
        val line = (verdict as? Verdict.Hazard)?.let { game.rows?.get(it.row)?.hazardLine }
        update { it.copy(camera = it.camera.copy(checking = false, matched = 0, cue = cue, hazardLine = line)) }
        when (cue) {
            CaptureCue.HAZARD -> commands += Command.Haptic(GameEffect.Reject)
            CaptureCue.FOUND -> found(frame.camera.row, frame.result.reticle)
            else -> Unit
        }
    }

    private fun found(row: Int?, crop: Pixels) {
        val active = game.hunt ?: return
        val progress = if (row == null) active.progress.tutorialPassed() else active.progress.targetFound(row)
        val updated = active.copy(progress = progress)
        game = game.copy(hunt = updated)
        persist(updated)
        if (row == null) {
            save(game.flags.copy(tutorialDone = true))
        } else {
            game.rows?.get(row)?.scientific?.let { save(game.flags.copy(foundSpecies = game.flags.foundSpecies + it)) }
        }
        publish(updated)
        update { it.copy(crop = crop) }
        commands += Command.Haptic(GameEffect.Confirm)
        show(Screen.Found(row))
    }

    private fun next(screen: Screen) {
        val found = screen as? Screen.Found ?: return
        val progress = game.hunt?.progress ?: return
        when {
            found.row == null -> show(Screen.Hunt)
            progress.complete -> show(Screen.Complete)
            else -> openCamera(progress.remaining.first().row)
        }
    }

    // A kid somewhere a target doesn't grow swaps it for the next species in the queue and keeps hunting on the same
    // camera; the grass tutorial has no queue, so its skip moves on to the hunt.
    private fun skip(screen: Screen) {
        val camera = screen as? Screen.Camera
        val active = game.hunt
        val rows = game.rows
        if (camera == null || active == null || rows == null) return
        commands += Command.CancelCapture
        game = game.copy(session = game.session + 1)
        val row = camera.row
        val progress = if (row == null) {
            active.progress.tutorialPassed()
        } else {
            active.progress.skip(row) { rows[it].genus }
        }
        val updated = active.copy(progress = progress)
        game = game.copy(hunt = updated)
        persist(updated)
        publish(updated)
        if (row == null) {
            save(game.flags.copy(tutorialDone = true))
            show(Screen.Hunt)
        } else {
            // The swap keeps the target's slot, so the camera opens on whatever now sits there.
            openCamera(progress.targets[active.progress.targets.indexOfFirst { it.row == row }].row)
        }
    }

    // An ended hunt leaves disk now, so a relaunch lands on Start, while Complete still shows its stops from memory.
    private fun finish() {
        commands += Command.ClearHunt
        show(Screen.Complete)
    }

    private fun toHunt() = show(if (game.hunt?.progress?.tutorialPending == true) Screen.Tutorial else Screen.Hunt)

    private fun endHunt() {
        game = game.copy(hunt = null)
        commands += Command.UseVerifier(null)
        update { it.copy(stops = emptyList(), crop = null, offline = false) }
        commands += Command.ClearHunt
    }

    private fun persist(active: ActiveHunt) {
        commands += Command.SaveHunt(active)
    }

    private fun save(updated: AppFlags) {
        // A run caching the old area would report its months against the new one, so it stops and the card resets.
        if (updated.region != game.flags.region && game.ui.areaCache != AreaCache.Idle) {
            if (game.ui.areaCache is AreaCache.Running) commands += Command.CancelCacheArea
            update { it.copy(areaCache = AreaCache.Idle) }
        }
        game = game.copy(flags = updated)
        commands += Command.SaveFlags(updated)
    }
}
