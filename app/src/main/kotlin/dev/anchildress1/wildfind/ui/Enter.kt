package dev.anchildress1.wildfind.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion
import kotlinx.coroutines.delay

/** Fades and lifts the content in on first show, [index] staggered behind earlier siblings; still when reduced. */
fun Modifier.rise(index: Int = 0): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(index * Motion.STAGGER.toLong())
        progress.animateTo(1f, tween(Motion.MOVE, easing = Motion.Decelerate))
    }
    val lift = RISE.value
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * lift * density
    }
}

/** Pops the content from nothing to full size, [index] staggered; the stars on the found and complete screens. */
fun Modifier.pop(index: Int = 0): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(index * Motion.STAGGER.toLong())
        scale.animateTo(1f, tween(Motion.BIG, easing = Motion.Overshoot))
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

private val RISE = 24.dp
