package dev.anchildress1.wildfind.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.core.verify.CaptureCue
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import dev.anchildress1.wildfind.game.CameraState
import dev.anchildress1.wildfind.ui.theme.Palette

/**
 * The reticle ring over the square the models read: a dark outer stroke for bright scenes, a style per verify state
 * so its shape (not only its color) changes, and three arcs that fill as a capture's frames match.
 */
fun DrawScope.drawRing(camera: CameraState, center: Offset, radius: Float) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(DASH.dp.toPx(), GAP.dp.toPx()))
    val (color, width, effect) = when (camera.cue) {
        CaptureCue.HAZARD -> Triple(Palette.HazardRing, 5.dp, dashed)
        CaptureCue.POINT_AT_PLANT, CaptureCue.PUT_IN_CIRCLE -> Triple(Color.White, 4.dp, dashed)
        CaptureCue.TAP_TO_FOCUS -> Triple(Color.White.copy(alpha = DIM_RING), 4.dp, null)
        else -> Triple(Color.White, 4.dp, null)
    }
    drawCircle(Color.Black.copy(alpha = SHADOW), radius + 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
    drawCircle(color, radius, center, style = Stroke(width.toPx(), pathEffect = effect))
    if (!camera.checking) return
    val arc = radius + 14.dp.toPx()
    repeat(VerifyStreak.FRAMES) { i ->
        drawArc(
            if (i < camera.matched) Palette.Wheat else Color.White.copy(alpha = DIM_ARC),
            startAngle = FIRST_ARC + i * ARC_STEP,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(center.x - arc, center.y - arc),
            size = Size(arc * 2, arc * 2),
            style = Stroke(8.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private const val DASH = 10f
private const val GAP = 8f
private const val SHADOW = 0.55f
private const val DIM_RING = 0.6f
private const val DIM_ARC = 0.35f
private const val FIRST_ARC = -84f
private const val ARC_STEP = 120f
private const val ARC_SWEEP = 106f
