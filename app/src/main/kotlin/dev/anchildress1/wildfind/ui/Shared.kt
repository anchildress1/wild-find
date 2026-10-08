package dev.anchildress1.wildfind.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion

/** The screen host's shared-transition scope, for the stop-to-camera container transform. */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current screen's enter/exit scope. */
val LocalScreenScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Morphs this container into whatever shares [key] on the next screen; reduced motion keeps the crossfade only. */
fun Modifier.container(key: Any): Modifier = composed {
    val shared = LocalSharedScope.current
    val screen = LocalScreenScope.current
    if (shared == null || screen == null || LocalReducedMotion.current) return@composed this
    with(shared) {
        sharedBounds(
            rememberSharedContentState(key),
            screen,
            boundsTransform = { _, _ -> tween(Motion.MOVE, easing = Motion.Decelerate) },
        )
    }
}

/** The shared key of the stop at [row] and the camera that opens from it. */
fun stopKey(row: Int?): String = "stop-$row"
