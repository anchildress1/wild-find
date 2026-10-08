package dev.anchildress1.wildfind.game

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
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.map.WorldMap
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.PlantGate
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.inat.InatClient
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

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
            state.update { it.copy(region = flags.region) }
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
            GameEvent.OpenMap -> show(Screen.Map(back = screen))
            GameEvent.ChangeRegion -> show(Screen.Region(back = null))
            GameEvent.LoadHunt -> load()
            is GameEvent.OpenCamera -> openCamera(event.row)
            GameEvent.Capture -> capture(screen)
            GameEvent.Next -> next(screen)
            GameEvent.ToHunt -> toHunt()
            GameEvent.FinishHunt -> show(Screen.Complete)
            GameEvent.HuntAgain -> endHunt().also { load() }
            GameEvent.Home -> endHunt().also { show(Screen.Start) }
            GameEvent.OpenGrownUps -> show(Screen.GrownUps(from = screen))
            GameEvent.EditRegion -> show(Screen.Map(back = screen))
            GameEvent.ReplayOpener -> show(Screen.Opener(back = screen))
            GameEvent.Back -> back(screen)
        }
    }

    private fun show(screen: Screen) {
        if (state.value.screen is Screen.Camera && screen !is Screen.Camera) verifier?.cancel()
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
            state.update { it.copy(locationFailed = true) }
            return fallback?.let(::show) ?: Unit
        }
        state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val region = graph.location.region()
            state.update { it.copy(locating = false) }
            when {
                region == null -> fallback?.let(::show)

                screen is Screen.Map -> state.update {
                    it.copy(mapFocus = MapFocus(region, (it.mapFocus?.serial ?: 0) + 1))
                }

                else -> pick(region)
            }
        }
    }

    /** The built-in map, once read. */
    suspend fun map(): WorldMap = graph.map.await()

    private fun pick(region: RegionKey) {
        val back = when (val screen = state.value.screen) {
            is Screen.Region -> screen.back

            // Through the area choice, back to wherever that came from.
            is Screen.Map -> (screen.back as? Screen.Region)?.back ?: screen.back?.takeUnless { it is Screen.Region }

            else -> null
        }
        if (region == flags.region && hunt != null && back != null) return show(back)
        save(flags.copy(region = region))
        state.update { it.copy(region = region) }
        endHunt()
        load()
    }

    private fun load() {
        val region = flags.region ?: return show(Screen.Region(back = null))
        show(Screen.Loading)
        viewModelScope.launch {
            val models = graph.models.await()
            var offline = false
            val result = withContext(Dispatchers.IO) {
                LocalListSource(
                    LocalSpecies(models.rows, models.names::rowOf),
                    pull = { query -> pull(query).also { if (it == null) offline = true } },
                    cached = graph.store::cached,
                    save = graph.store::cache,
                ).load(models.tableVersion, region, Locale.getDefault().language, LocalDate.now().monthValue)
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

    // One query; a 429 waits out Retry-After, then the cache answers (PRD Failure Handling).
    private fun pull(query: SpeciesCountsQuery): List<Sighting>? = when (val pull = graph.inat.pull(query)) {
        is InatClient.Pull.Pulled -> pull.sightings

        is InatClient.Pull.RateLimited -> {
            TimeUnit.SECONDS.sleep((pull.retryAfterSeconds ?: 0).coerceAtMost(MAX_RATE_LIMIT_WAIT_S))
            null
        }

        is InatClient.Pull.Failed -> null
    }

    private fun startHunt(models: Models, active: ActiveHunt) {
        hunt = active
        verifier = models.verifier(active.local)
        publish(models, active)
    }

    private fun publish(models: Models, active: ActiveHunt) = state.update { ui ->
        ui.copy(
            stops = active.progress.targets.map {
                Stop(it.row, it.common, models.rows[it.row].type, it.row in active.progress.found)
            },
        )
    }

    private fun openCamera(row: Int?) {
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
        val started = capturing.capture(goal) { frame -> viewModelScope.launch { captured(camera, frame) } }
        if (started) state.update { it.copy(camera = it.camera.copy(checking = true, matched = 0)) }
    }

    private fun captured(camera: Screen.Camera, frame: CapturedFrame) {
        // A capture that finishes after its screen left changes nothing.
        if (state.value.screen != camera) return
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

    private companion object {
        // Retry-After can ask for minutes; a kid on the loading screen gets the cache after this long at most.
        const val MAX_RATE_LIMIT_WAIT_S = 30L
    }
}
