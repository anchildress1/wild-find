package dev.anchildress1.wildfind.game

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.wildfind.Graph
import dev.anchildress1.wildfind.Models
import dev.anchildress1.wildfind.camera.CaptureVerifier
import dev.anchildress1.wildfind.camera.CapturedFrame
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.HuntPick
import dev.anchildress1.wildfind.core.hunt.LocalListResult
import dev.anchildress1.wildfind.core.hunt.LocalListSource
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.inat.InatLocale
import dev.anchildress1.wildfind.core.inat.RetryWindow
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.map.Places
import dev.anchildress1.wildfind.core.map.WorldMap
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.PlantGate
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.inat.InatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * The game: one hunt at a time, saved on every change so it survives process death (S38).
 *
 * @param graph the app's dependencies
 */
@Suppress("TooManyFunctions")
class GameViewModel(private val graph: Graph) : ViewModel() {
    private val state = MutableStateFlow(GameState())
    private val effects = Channel<GameEffect>(Channel.BUFFERED)
    private var flags = AppFlags()
    private var hunt: ActiveHunt? = null
    private var verifier: CaptureVerifier? = null
    private var models: Models? = null
    private val retry = RetryWindow(SystemClock::elapsedRealtime)
    private var loading: Job? = null

    // Bumped on every capture and whenever the camera opens or leaves; a frame from an older session is dropped, so
    // a cancelled capture can't land in a new one on the same target and award its star.
    private var session = 0

    // Store writes run one at a time and in order, so a quick find-then-finish never lands out of order.
    private val disk = Dispatchers.IO.limitedParallelism(1)

    /** Screen state. */
    val ui: StateFlow<GameState> = state.asStateFlow()

    /** Haptics. */
    val effect: Flow<GameEffect> = effects.receiveAsFlow()

    /** The current hunt's capture loop, for the camera to feed; null before a hunt starts. */
    val capture: CaptureVerifier? get() = verifier

    /** The camera analysis thread. */
    val analysis get() = graph.analysis

    init {
        viewModelScope.launch {
            models = graph.models.await()
            state.update { it.copy(camera = it.camera.copy(ready = true)) }
        }
        viewModelScope.launch {
            flags = withContext(disk) { graph.store.flags() }
            state.update { it.copy(region = flags.region, locationFailed = flags.locationDenied) }
            relabel()
            if (flags.openerSeen) resume() else show(Screen.Opener(back = null))
        }
    }

    /** The single entry point for what the kid did. */
    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: GameEvent) {
        val screen = state.value.screen
        when (event) {
            GameEvent.OpenerDone -> openerDone(screen)
            is GameEvent.LocationAnswer -> located(event.granted)
            is GameEvent.PickRegion -> pick(event.region)
            GameEvent.OpenMap -> openMap(Screen.Map(back = screen))
            GameEvent.ChangeRegion -> show(Screen.Region(back = null))
            GameEvent.LoadHunt -> load()
            is GameEvent.OpenCamera -> openCamera(event.row)
            GameEvent.Capture -> capture(screen)
            GameEvent.Next -> next(screen)
            GameEvent.Skip -> skip(screen)
            GameEvent.ToHunt -> toHunt()
            GameEvent.FinishHunt -> finish()
            GameEvent.HuntAgain -> endHunt().also { load() }
            GameEvent.Home -> endHunt().also { show(Screen.Start) }
            GameEvent.OpenGrownUps -> show(Screen.GrownUps(from = screen))
            GameEvent.EditRegion -> openMap(Screen.Map(back = screen))
            GameEvent.ReplayOpener -> show(Screen.Opener(back = screen))
            GameEvent.Back -> back(screen)
        }
    }

    private fun show(screen: Screen) {
        if (state.value.screen is Screen.Camera && screen != state.value.screen) {
            verifier?.cancel()
            session++
        }
        state.update { it.copy(screen = screen) }
    }

    private fun back(screen: Screen) {
        when (screen) {
            is Screen.Opener -> screen.back?.let(::show)
            is Screen.Region -> screen.back?.let(::show)
            is Screen.Map -> screen.back?.let(::show)
            is Screen.GrownUps -> show(screen.from)
            is Screen.Camera, is Screen.Found -> toHunt()
            Screen.Complete -> onEvent(GameEvent.Home)
            else -> Unit
        }
    }

    // First launch, or after the area is set: the saved hunt if one survives, else a fresh start.
    private suspend fun resume() {
        if (flags.region == null) return show(Screen.Region(back = null))
        show(Screen.Loading)
        val models = graph.models.await()
        val saved = withContext(disk) { graph.store.hunt(models.tableVersion) }
        if (saved == null) return show(Screen.Start)
        startHunt(models, saved)
        show(
            when {
                saved.progress.tutorialPending -> Screen.Tutorial
                saved.progress.complete -> Screen.Complete
                else -> Screen.Hunt
            },
        )
    }

    private fun openerDone(screen: Screen) {
        val back = (screen as? Screen.Opener)?.back
        if (back != null) return show(back)
        save(flags.copy(openerSeen = true))
        viewModelScope.launch { resume() }
    }

    // From the area choice, a found area starts the hunt and anything else opens the map; on the map, Locate only
    // recenters. A denial is final: neither button asks again.
    private fun located(granted: Boolean) {
        val screen = state.value.screen
        val fallback = Screen.Map(back = screen).takeIf { screen is Screen.Region }
        if (!granted) {
            save(flags.copy(locationDenied = true))
            state.update { it.copy(locationFailed = true) }
            return fallback?.let(::show) ?: Unit
        }
        state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val region = graph.location.region()
            state.update { it.copy(locating = false) }
            // A fix that lands after the kid moved on (picked on the map, went back) must not start a hunt.
            if (state.value.screen != screen) return@launch
            when {
                region == null -> fallback?.let(::show)

                screen is Screen.Map -> state.update {
                    it.copy(mapFocus = MapFocus(region, (it.mapFocus?.serial ?: 0) + 1))
                }

                else -> pick(region)
            }
        }
    }

    // With location already allowed, the map opens on the rough location, zoomed in enough to pick; it never asks
    // here, so without permission (or a fix) it stays on the whole world.
    private fun openMap(map: Screen.Map) {
        show(map)
        viewModelScope.launch {
            val region = graph.location.region() ?: return@launch
            if (state.value.screen === map) {
                state.update { it.copy(mapFocus = MapFocus(region, (it.mapFocus?.serial ?: 0) + 1, opening = true)) }
            }
        }
    }

    private fun relabel() {
        viewModelScope.launch {
            val places = graph.places.await()
            state.update { ui -> ui.copy(regionLabel = ui.region?.let { Places.label(it, places.nameAt(it)) }) }
        }
    }

    /** Offline place names, once read. */
    suspend fun places(): Places = graph.places.await()

    /** The built-in map, once read. */
    suspend fun map(): WorldMap = graph.map.await()

    private fun pick(region: RegionKey) {
        val back = when (val screen = state.value.screen) {
            is Screen.Region -> screen.back

            // Through the area choice, back to wherever that came from.
            is Screen.Map -> (screen.back as? Screen.Region)?.back ?: screen.back?.takeUnless { it is Screen.Region }

            else -> null
        }
        // Confirming the area already set changes nothing, hunt or not: no new pull, just back where the kid came from.
        if (region == flags.region && back != null) return show(back)
        save(flags.copy(region = region))
        state.update { it.copy(region = region) }
        relabel()
        endHunt()
        load()
    }

    private fun load() {
        val region = flags.region ?: return show(Screen.Region(back = null))
        show(Screen.Loading)
        // One pull at a time: a newer pick or retry replaces the older one, whichever would have finished last.
        loading?.cancel()
        loading = viewModelScope.launch {
            val models = graph.models.await()
            var offline = false
            val result = withContext(Dispatchers.IO) {
                LocalListSource(
                    LocalSpecies(models.rows, models.names::rowOf),
                    pull = { query -> pull(query).also { if (it == null) offline = true } },
                    cached = graph.store::cached,
                    save = graph.store::cache,
                ).load(
                    models.tableVersion,
                    region,
                    InatLocale.of(Locale.getDefault().toLanguageTag()),
                    LocalDate.now().monthValue,
                )
            }
            val local = (result as? LocalListResult.Ready)?.local
            val planned = local?.let { HuntPick(models.rows).next(it, flags.tutorialDone) }
            when {
                result == LocalListResult.NeedsSignal -> show(Screen.NeedsSignal)

                local == null || planned == null || planned.targets.size < HuntPick.TARGETS ->
                    show(Screen.NotEnough)

                else -> {
                    val fresh = ActiveHunt.start(planned, local, region)
                    startHunt(models, fresh)
                    persist(fresh)
                    state.update { it.copy(offline = offline) }
                    show(if (fresh.progress.tutorialPending) Screen.Tutorial else Screen.Hunt)
                }
            }
        }
    }

    // One query; after a 429 nothing goes to iNat until Retry-After passes, and the cache answers (PRD Failure
    // Handling), so the widened query never fires inside the window.
    private fun pull(query: SpeciesCountsQuery): List<Sighting>? {
        if (retry.open) return null
        return when (val pull = graph.inat.pull(query)) {
            is InatClient.Pull.Pulled -> pull.sightings

            is InatClient.Pull.RateLimited -> {
                retry.rateLimited(pull.retryAfterSeconds)
                null
            }

            is InatClient.Pull.Failed -> null
        }
    }

    private fun startHunt(models: Models, active: ActiveHunt) {
        hunt = active
        verifier = models.verifier(active.local)
        publish(models, active)
    }

    private fun publish(models: Models, active: ActiveHunt) = state.update { ui ->
        ui.copy(
            stops = active.progress.targets.map {
                val species = models.rows[it.row]
                Stop(it.row, it.common, species.type, it.row in active.progress.found, species.description)
            },
        )
    }

    private fun openCamera(row: Int?) {
        if (row != null && hunt?.progress?.found?.contains(row) == true) return
        state.update { it.copy(camera = CameraState(ready = models != null)) }
        show(Screen.Camera(row))
    }

    private fun capture(screen: Screen) {
        val camera = screen as? Screen.Camera ?: return
        val active = hunt
        val capturing = verifier
        val models = models
        if (active == null || capturing == null || models == null) return
        val goal: Goal = camera.row?.let { active.goal(models.table, models.genus, it) } ?: models.tutorial
        val id = session + 1
        val started = capturing.capture(goal) { frame -> viewModelScope.launch { captured(camera, id, frame) } }
        // Frames post to the main thread, so they arrive after this; a refused tap leaves the running session alone.
        if (started) {
            session = id
            state.update { it.copy(camera = it.camera.copy(checking = true, matched = 0)) }
        }
    }

    private fun captured(camera: Screen.Camera, id: Int, frame: CapturedFrame) {
        // A capture that finishes after its screen left, or after a newer capture began, changes nothing.
        if (id != session || state.value.screen != camera) return
        val verdict = frame.verdict
        if (verdict is Verdict.Matching) {
            return state.update { it.copy(camera = it.camera.copy(matched = verdict.frames)) }
        }
        val cue = CaptureCue.of(verdict, PlantGate.isPlant(frame.result.fullShare))
        state.update { it.copy(camera = it.camera.copy(checking = false, matched = 0, cue = cue)) }
        when (cue) {
            CaptureCue.HAZARD -> effects.trySend(GameEffect.Reject)
            CaptureCue.FOUND -> found(camera.row, frame)
            else -> Unit
        }
    }

    private fun found(row: Int?, frame: CapturedFrame) {
        val active = hunt ?: return
        val progress = if (row == null) active.progress.tutorialPassed() else active.progress.targetFound(row)
        val updated = active.copy(progress = progress)
        hunt = updated
        persist(updated)
        if (row == null) save(flags.copy(tutorialDone = true))
        models?.let { publish(it, updated) }
        state.update { it.copy(crop = frame.result.reticle) }
        effects.trySend(GameEffect.Confirm)
        show(Screen.Found(row))
    }

    private fun next(screen: Screen) {
        val found = screen as? Screen.Found ?: return
        val progress = hunt?.progress ?: return
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
        val active = hunt
        val models = models
        if (camera == null || active == null || models == null) return
        verifier?.cancel()
        session++
        val row = camera.row
        val progress = if (row ==
            null
        ) {
            active.progress.tutorialPassed()
        } else {
            active.progress.skip(row, models.genus::get)
        }
        val updated = active.copy(progress = progress)
        hunt = updated
        persist(updated)
        publish(models, updated)
        if (row == null) {
            save(flags.copy(tutorialDone = true))
            show(Screen.Hunt)
        } else {
            // The swap keeps the target's slot, so the camera opens on whatever now sits there.
            openCamera(progress.targets[active.progress.targets.indexOfFirst { it.row == row }].row)
        }
    }

    // An ended hunt leaves disk now, so a relaunch lands on Start, while Complete still shows its stops from memory.
    private fun finish() {
        viewModelScope.launch(disk) { graph.store.clearHunt() }
        show(Screen.Complete)
    }

    private fun toHunt() = show(if (hunt?.progress?.tutorialPending == true) Screen.Tutorial else Screen.Hunt)

    private fun endHunt() {
        hunt = null
        verifier = null
        state.update { it.copy(stops = emptyList(), crop = null, offline = false) }
        viewModelScope.launch(disk) { graph.store.clearHunt() }
    }

    private fun persist(active: ActiveHunt) {
        viewModelScope.launch(disk) { graph.store.save(active, graph.models.await().tableVersion) }
    }

    private fun save(updated: AppFlags) {
        flags = updated
        viewModelScope.launch(disk) { graph.store.save(updated) }
    }
}
