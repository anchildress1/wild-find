package dev.anchildress1.wildfind.game

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anchildress1.wildfind.Graph
import dev.anchildress1.wildfind.Models
import dev.anchildress1.wildfind.camera.CaptureVerifier
import dev.anchildress1.wildfind.core.game.Command
import dev.anchildress1.wildfind.core.game.Game
import dev.anchildress1.wildfind.core.game.GameEffect
import dev.anchildress1.wildfind.core.game.GameEvent
import dev.anchildress1.wildfind.core.game.GameInput
import dev.anchildress1.wildfind.core.game.GameRules
import dev.anchildress1.wildfind.core.game.GameState
import dev.anchildress1.wildfind.core.game.Outcome
import dev.anchildress1.wildfind.core.game.Screen
import dev.anchildress1.wildfind.core.hunt.LocalListSource
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.inat.InatLocale
import dev.anchildress1.wildfind.core.inat.RetryWindow
import dev.anchildress1.wildfind.core.map.Places
import dev.anchildress1.wildfind.core.map.WorldMap
import dev.anchildress1.wildfind.core.region.RegionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * Runs [GameRules] and the commands it returns: disk, models, location, iNat, and haptics.
 *
 * @param graph the app's dependencies
 */
class GameViewModel(private val graph: Graph) : ViewModel() {
    private val rules = GameRules()
    private var game = Game()
    private val state = MutableStateFlow(game.ui)
    private val effects = Channel<GameEffect>(Channel.BUFFERED)
    private var verifier: CaptureVerifier? = null
    private var models: Models? = null
    private val retry = RetryWindow(SystemClock::elapsedRealtime)
    private var loading: Job? = null
    private val areaRun = AreaRun()

    /** Screen state. */
    val ui: StateFlow<GameState> = state.asStateFlow()

    /** Haptics. */
    val effect: Flow<GameEffect> = effects.receiveAsFlow()

    /** The current hunt's capture loop, for the camera to feed; null before a hunt starts. */
    val capture: CaptureVerifier? get() = verifier

    /** The camera analysis thread. */
    val analysis get() = graph.analysis

    init {
        viewModelScope.launch { models() }
        viewModelScope.launch { dispatch(Outcome.FlagsRead(withContext(graph.disk) { graph.store.flags() })) }
    }

    /** The single entry point for what the kid did. */
    fun onEvent(event: GameEvent) = dispatch(event)

    /** Offline place names, once read. */
    suspend fun places(): Places = graph.places.await()

    /** The built-in map, once read. */
    suspend fun map(): WorldMap = graph.map.await()

    // Commands may dispatch again before this returns; each sees the game the previous dispatch left.
    private fun dispatch(input: GameInput) {
        val step = rules.reduce(game, input)
        game = step.game
        state.value = game.ui
        step.commands.forEach(::run)
    }

    // The rules need the table rows before any hunt starts, so whoever awaits the models first hands them over.
    private suspend fun models(): Models = graph.models.await().also {
        if (models == null) {
            models = it
            dispatch(Outcome.ModelsReady(it.rows))
        }
    }

    private fun run(command: Command) {
        when (command) {
            is Command.SaveFlags -> graph.write { save(command.flags) }

            is Command.SaveHunt -> graph.write { save(command.hunt, graph.models.await().tableVersion) }

            Command.ClearHunt -> graph.write { clearHunt() }

            is Command.UseVerifier -> useVerifier(command.local)

            Command.CancelCapture -> verifier?.cancel()

            is Command.Capture -> capture(command)

            Command.Relabel -> viewModelScope.launch { dispatch(Outcome.PlacesRead(graph.places.await())) }

            Command.ReadHunt -> viewModelScope.launch {
                val tableVersion = models().tableVersion
                dispatch(Outcome.SavedHunt(withContext(graph.disk) { graph.store.hunt(tableVersion) }))
            }

            is Command.Pull -> pull(command.region)

            is Command.CacheArea, Command.CancelCacheArea ->
                areaRun.on(command, viewModelScope) { region ->
                    val done = cacheArea(graph, models(), region, retry) {
                        viewModelScope.launch { dispatch(Outcome.AreaReport(region, it, finished = false)) }
                    }
                    dispatch(Outcome.AreaReport(region, done, finished = true))
                }

            is Command.Locate -> viewModelScope.launch {
                dispatch(Outcome.Located(command.from, graph.location.region()))
            }

            is Command.LocateMap -> locateMap(command.map)

            is Command.Haptic -> effects.trySend(command.effect)
        }
    }

    private fun useVerifier(local: Set<Int>?) {
        verifier = local?.let { checkNotNull(models).verifier(it) }
    }

    private fun locateMap(map: Screen.Map) {
        viewModelScope.launch { graph.location.region()?.let { dispatch(Outcome.MapLocated(map, it)) } }
    }

    private fun capture(command: Command.Capture) {
        val models = checkNotNull(models)
        val goal = command.camera.row?.let { command.hunt.goal(models.table, models.genus, it) } ?: models.tutorial
        val started = verifier?.capture(goal) { frame ->
            viewModelScope.launch {
                dispatch(Outcome.Frame(command.camera, command.session, frame.result, frame.verdict))
            }
        } ?: false
        // Frames post to the main thread, so they arrive after this.
        if (started) dispatch(Outcome.CaptureStarted(command.session))
    }

    // One pull at a time: a newer pick or retry replaces the older one, whichever would have finished last.
    private fun pull(region: RegionKey) {
        loading?.cancel()
        loading = viewModelScope.launch {
            val models = models()
            var offline = false
            val result = withContext(Dispatchers.IO) {
                LocalListSource(
                    LocalSpecies(models.rows, models.names::rowOf),
                    pull = { query -> retry.pull { graph.inat.pull(query) }.also { if (it == null) offline = true } },
                    cached = graph.store::cached,
                    save = graph.store::cache,
                ).load(
                    models.tableVersion,
                    region,
                    InatLocale.of(Locale.getDefault().toLanguageTag()),
                    LocalDate.now().monthValue,
                )
            }
            dispatch(Outcome.Pulled(region, result, offline))
        }
    }
}
