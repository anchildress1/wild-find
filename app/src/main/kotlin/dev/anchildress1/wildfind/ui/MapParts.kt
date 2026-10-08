package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.map.Heading
import dev.anchildress1.wildfind.core.map.LandMap
import dev.anchildress1.wildfind.core.map.MapCamera
import dev.anchildress1.wildfind.ui.theme.Palette

private val Ocean = Color(0xFFCBDAD3)
private val Land = Color(0xFFE9EBD8)
private val ButtonShape = RoundedCornerShape(24.dp)
private val PadShape = RoundedCornerShape(14.dp)

/**
 * The land rings drawn under [camera], with copies a turn east and west so the date line never shows a gap, and the
 * whole-degree cell under the crosshairs outlined once picking unlocks. Paths are built once in degrees and only
 * transformed per frame.
 */
@Composable
fun LandCanvas(land: LandMap?, camera: MapCamera) {
    val paths = remember(land) {
        land?.levels?.map { rings ->
            Path().apply {
                fillType = PathFillType.EvenOdd
                rings.forEach { ring ->
                    moveTo(ring[0], -ring[1])
                    for (i in 2 until ring.size step 2) lineTo(ring[i], -ring[i + 1])
                    close()
                }
            }
        }
    }
    val cell = remember { PathEffect.dashPathEffect(floatArrayOf(CELL_DASH, CELL_DASH)) }
    Canvas(Modifier.fillMaxSize().background(Ocean)) {
        val scale = (size.width / camera.span).toFloat()
        paths?.getOrNull(camera.level)?.let { path ->
            for (turn in -1..1) {
                withTransform({
                    translate(
                        size.width / 2 + ((turn * TURN - camera.lng) * scale).toFloat(),
                        size.height / 2 + (camera.lat * scale).toFloat(),
                    )
                    scale(scale, scale, Offset.Zero)
                }) {
                    drawPath(path, Land)
                    drawPath(path, Palette.Moss, style = Stroke(COAST.dp.toPx() / scale))
                }
            }
        }
        if (camera.canPick) {
            val region = camera.region
            val left = camera.x(region.lng - HALF, size.width)
            val top = camera.y(region.lat + HALF, size.width, size.height)
            drawRect(
                Palette.Forest,
                Offset(left, top),
                Size(scale, scale),
                style = Stroke(2.dp.toPx(), pathEffect = cell),
            )
        }
    }
}

/** The fixed crosshairs: an ink cross over a cream halo, so it reads on land and water alike. */
@Composable
fun Crosshairs(modifier: Modifier = Modifier) {
    Canvas(modifier.size(72.dp)) {
        val c = size.width / 2
        val arm = size.width * ARM
        val gap = size.width * GAP
        val segments = listOf(
            Offset(c, c - arm) to Offset(c, c - gap),
            Offset(c, c + gap) to Offset(c, c + arm),
            Offset(c - arm, c) to Offset(c - gap, c),
            Offset(c + gap, c) to Offset(c + arm, c),
        )
        for ((color, width) in listOf(Palette.Paper to 8.dp, Palette.Ink to 3.dp)) {
            segments.forEach { (a, b) -> drawLine(color, a, b, width.toPx(), StrokeCap.Round) }
        }
        drawCircle(Palette.Paper, 5.25.dp.toPx(), Offset(c, c))
        drawCircle(Palette.Ink, 4.dp.toPx(), Offset(c, c))
    }
}

/** Locate (when location was never denied) and the zoom pair, 48 dp each. */
@Composable
fun Controls(canLocate: Boolean, onLocation: (Boolean) -> Unit, modifier: Modifier, onZoom: (Double) -> Unit) {
    val locate = rememberLocation(onLocation)
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canLocate) {
            Floating(ButtonShape) {
                MapButton(WildIcons.Locate, stringResource(R.string.map_locate), locate)
            }
        }
        Floating(ButtonShape) {
            Column {
                MapButton(WildIcons.Plus, stringResource(R.string.map_zoom_in)) { onZoom(ZOOM_STEP) }
                Box(Modifier.size(48.dp, 1.5.dp).background(Palette.Line))
                MapButton(WildIcons.Minus, stringResource(R.string.map_zoom_out)) { onZoom(1 / ZOOM_STEP) }
            }
        }
    }
}

/** One whole degree per tap; the TalkBack path that needs no dragging. */
@Composable
fun ArrowPad(onStep: (Heading) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Spacer(Modifier.size(50.dp, 48.dp))
            Arrow(WildIcons.Up, R.string.map_north) { onStep(Heading.NORTH) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Arrow(WildIcons.Left, R.string.map_west) { onStep(Heading.WEST) }
            Spacer(Modifier.size(48.dp))
            Arrow(WildIcons.Right, R.string.map_east) { onStep(Heading.EAST) }
        }
        Row {
            Spacer(Modifier.size(50.dp, 48.dp))
            Arrow(WildIcons.Down, R.string.map_south) { onStep(Heading.SOUTH) }
        }
    }
}

@Composable
private fun Arrow(icon: ImageVector, description: Int, onClick: () -> Unit) {
    Floating(PadShape) { MapButton(icon, stringResource(description), onClick) }
}

@Composable
private fun Floating(shape: RoundedCornerShape, content: @Composable () -> Unit) {
    Box(Modifier.shadow(4.dp, shape).background(Palette.Paper, shape)) { content() }
}

@Composable
private fun MapButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, Modifier.size(22.dp), tint = Palette.Ink)
    }
}

private const val TURN = 360.0
private const val HALF = 0.5
private const val COAST = 1f
private const val CELL_DASH = 14f
private const val ARM = 0.44f
private const val GAP = 0.14f
private const val ZOOM_STEP = 2.0
