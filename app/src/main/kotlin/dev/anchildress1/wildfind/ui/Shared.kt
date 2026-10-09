package dev.anchildress1.wildfind.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette

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

/** A full-width 48 dp underlined Forest text button for the quiet way out of a screen. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick,
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Palette.Forest),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            textDecoration = TextDecoration.Underline,
            textAlign = TextAlign.Center,
        )
    }
}
