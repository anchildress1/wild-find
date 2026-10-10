package dev.anchildress1.wildfind.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.WildFindApp
import dev.anchildress1.wildfind.core.game.GameEffect
import dev.anchildress1.wildfind.core.game.GameEvent
import dev.anchildress1.wildfind.core.game.GameState
import dev.anchildress1.wildfind.core.game.Screen
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.hunt.forMonth
import dev.anchildress1.wildfind.core.map.Places
import dev.anchildress1.wildfind.core.map.WorldMap
import dev.anchildress1.wildfind.game.GameViewModel
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion
import java.time.LocalDate

private val factory = viewModelFactory {
    initializer { GameViewModel((this[APPLICATION_KEY] as WildFindApp).graph) }
}

/** The game's host: collects state and effects, binds back and haptics, and animates every screen change. */
@Composable
fun GameRoute(vm: GameViewModel = viewModel(factory = factory)) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val view = LocalView.current
    LaunchedEffect(vm) {
        vm.effect.collect {
            view.performHapticFeedback(
                when (it) {
                    GameEffect.Confirm -> HapticFeedbackConstants.CONFIRM
                    GameEffect.Reject -> HapticFeedbackConstants.REJECT
                },
            )
        }
    }
    BackHandler(enabled = hasBack(state.screen)) { vm.onEvent(GameEvent.Back) }
    val reduced = LocalReducedMotion.current
    SharedTransitionLayout {
        // Keyed by screen but fed the whole state, so a leaving screen fades out with the data it last showed.
        AnimatedContent(
            state,
            transitionSpec = { transition(reduced) },
            contentKey = { it.screen },
            label = "screen",
        ) { shown ->
            CompositionLocalProvider(
                LocalSharedScope provides this@SharedTransitionLayout,
                LocalScreenScope provides this,
            ) {
                ScreenFor(shown.screen, shown, vm)
            }
        }
    }
}

private fun hasBack(screen: Screen) = when (screen) {
    is Screen.Opener -> screen.back != null
    is Screen.Region -> screen.back != null
    is Screen.Map -> screen.back != null
    is Screen.GrownUps, is Screen.Camera, is Screen.Found, Screen.Complete -> true
    else -> false
}

// Move 300, emphasized decelerate; with animations off, a crossfade only.
private fun AnimatedContentTransitionScope<GameState>.transition(reduced: Boolean): ContentTransform {
    val fade = fadeIn(tween(Motion.MOVE)) togetherWith fadeOut(tween(Motion.QUICK))
    if (reduced) return fade
    return (
        fadeIn(tween(Motion.MOVE)) +
            slideInHorizontally(tween(Motion.MOVE, easing = Motion.Decelerate)) { it / ENTER_SHARE }
        )
        .togetherWith(fadeOut(tween(Motion.QUICK)))
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ScreenFor(screen: Screen, state: GameState, vm: GameViewModel) {
    val on = vm::onEvent
    when (screen) {
        Screen.Starting -> BlankScreen()

        is Screen.Opener -> OpenerScreen(screen.back != null) { on(GameEvent.OpenerDone) }

        is Screen.Region -> RegionScreen(
            state.locating,
            state.locationFailed,
            onLocation = { on(GameEvent.LocationAnswer(it)) },
            onMap = { on(GameEvent.OpenMap) },
        )

        is Screen.Map -> MapRoute(state, vm)

        Screen.Loading -> LoadingScreen()

        Screen.NeedsSignal -> NeedsSignalScreen(
            { on(GameEvent.LoadHunt) },
            { on(GameEvent.ChangeRegion) },
            { on(GameEvent.OpenGrownUps) },
        )

        Screen.NotEnough -> NotEnoughScreen { on(GameEvent.ChangeRegion) }

        Screen.Start -> StartScreen(state.regionLabel, { on(GameEvent.LoadHunt) }, { on(GameEvent.OpenGrownUps) })

        Screen.Tutorial -> TutorialScreen({ on(GameEvent.OpenCamera(null)) }, { on(GameEvent.OpenGrownUps) })

        Screen.Hunt -> HuntScreen(
            state.stops,
            state.regionLabel,
            state.offline,
            onStop = { on(GameEvent.OpenCamera(it)) },
            onFinish = { on(GameEvent.FinishHunt) },
            onGrownUps = { on(GameEvent.OpenGrownUps) },
        )

        is Screen.Camera -> CameraScreen(
            cameraTarget(screen.row, state),
            state.camera,
            vm.capture,
            vm.analysis,
            onCapture = { on(GameEvent.Capture) },
            onSkip = { on(GameEvent.Skip) },
            onHint = { screen.row?.let { on(GameEvent.RevealHint(it)) } },
            onBack = { on(GameEvent.Back) },
            onDismissHazard = { on(GameEvent.DismissHazard) },
        )

        is Screen.Found -> FoundScreen(
            foundInfo(screen.row, state),
            state.crop,
            onNext = { on(GameEvent.Next) },
            onHunt = { on(GameEvent.ToHunt) },
        )

        Screen.Complete -> CompleteScreen(state.stops, { on(GameEvent.HuntAgain) }, { on(GameEvent.Home) })

        is Screen.GrownUps -> GrownUpsScreen(
            state.regionLabel,
            state.areaCache,
            onBack = { on(GameEvent.Back) },
            onArea = { on(GameEvent.EditRegion) },
            onReplay = { on(GameEvent.ReplayOpener) },
            onCache = { on(GameEvent.CacheArea) },
        )
    }
}

@Composable
private fun MapRoute(state: GameState, vm: GameViewModel) {
    val map by produceState<WorldMap?>(null) { value = vm.map() }
    val places by produceState<Places?>(null) { value = vm.places() }
    MapScreen(
        map,
        places,
        state.mapFocus,
        canLocate = !state.locationFailed,
        locating = state.locating,
        onLocation = { vm.onEvent(GameEvent.LocationAnswer(it)) },
        onPick = { vm.onEvent(GameEvent.PickRegion(it)) },
        onBack = { vm.onEvent(GameEvent.Back) },
    )
}

@Composable
private fun cameraTarget(row: Int?, state: GameState): CameraTarget {
    val stop =
        row?.let(state::stop)
            ?: return CameraTarget(null, stringResource(R.string.grass), PlantType.GRASS, 0, state.stops.size)
    return CameraTarget(
        row,
        stop.name,
        stop.type,
        state.stops.indexOf(stop) + 1,
        state.stops.size,
        stop.description,
        stop.canSkip,
        stop.hints.forMonth(LocalDate.now().monthValue, northern = (state.region?.lat ?: 0) >= 0),
        state.hintsShown[row] ?: 0,
    )
}

@Composable
private fun foundInfo(row: Int?, state: GameState): FoundInfo {
    val next = state.stops.firstOrNull { !it.found }?.name
    val stop = row?.let(state::stop)
    val name = stop?.name ?: stringResource(R.string.grass)
    return FoundInfo(name, state.stars, state.stops.size, next, tutorial = row == null, stop?.description)
}

private const val ENTER_SHARE = 8
