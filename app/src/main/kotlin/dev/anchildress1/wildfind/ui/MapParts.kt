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
import dev.anchildress1.wildfind.core.map.MapCamera
import dev.anchildress1.wildfind.core.map.MapLayer
import dev.anchildress1.wildfind.core.map.WorldMap
import dev.anchildress1.wildfind.ui.theme.Palette

private val Ocean = Color(0xFFCBDAD3)
private val Land = Color(0xFFE9EBD8)
private val ButtonShape = RoundedCornerShape(24.dp)
private val PadShape = RoundedCornerShape(14.dp)

/**
 * The built-in map under [camera]: land, then state lines (dashed, area detail only), then country borders (solid
 * and heaviest), each copied a turn east and west so the date line never shows a gap. No place names, ever. Paths
 * are built once in degrees and only transformed per frame; the whole-degree cell under the crosshairs is outlined
 * once picking unlocks.
 */
@Composable
fun MapCanvas(map: WorldMap?, camera: MapCamera) {
    val paths = remember(map) { map?.let(::paths) }
    val cell = remember { PathEffect.dashPathEffect(floatArrayOf(CELL_DASH, CELL_DASH)) }
    Canvas(Modifier.fillMaxSize().background(Ocean)) {
        val scale = (size.width / camera.span).toFloat()
        val level = paths?.get(camera.level)
        for (turn in -1..1) {
            if (level == null) break
            withTransform({
                translate(
                    size.width / 2 + ((turn * TURN - camera.lng) * scale).toFloat(),
                    size.height / 2 + (camera.lat * scale).toFloat(),
                )
                scale(scale, scale, Offset.Zero)
            }) {
                // Stroke widths and dashes are in degrees here, so each divides by the scale to stay fixed on screen.
                drawPath(level.land, Land)
                drawPath(level.land, Palette.Moss, style = Stroke(COAST.dp.toPx() / scale))
                drawPath(
                    level.states,
                    Palette.Ink2,
                    style = Stroke(
                        STATE.dp.toPx() / scale,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(
                                STATE_DASH.dp.toPx() / scale,
                                STATE_GAP.dp.toPx() / scale,
                            ),
                        ),
                    ),
                )
                drawPath(level.borders, Palette.Paper, style = Stroke(BORDER_HALO.dp.toPx() / scale))
                drawPath(level.borders, Palette.Ink, style = Stroke(BORDER.dp.toPx() / scale))
            }
        }
        if (camera.canPick) {
            val region = camera.region
            drawRect(
                Palette.Forest,
                Offset(camera.x(region.lng - HALF, size.width), camera.y(region.lat + HALF, size.width, size.height)),
                Size(scale, scale),
                style = Stroke(2.dp.toPx(), pathEffect = cell),
            )
        }
    }
}

private class Level(val land: Path, val borders: Path, val states: Path)

private fun paths(map: WorldMap): List<Level> = (0..1).map { level ->
    Level(
        path(map.shapes(MapLayer.LAND, level), closed = true),
        path(map.shapes(MapLayer.BORDERS, level), closed = false),
        path(map.shapes(MapLayer.STATES, level), closed = false),
    )
}

private fun path(shapes: List<FloatArray>, closed: Boolean) = Path().apply {
    fillType = PathFillType.EvenOdd
    shapes.forEach { shape ->
        moveTo(shape[0], -shape[1])
        for (i in 2 until shape.size step 2) lineTo(shape[i], -shape[i + 1])
        if (closed) close()
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
private const val STATE = 1.5f
private const val STATE_DASH = 6f
private const val STATE_GAP = 4f
private const val BORDER = 2.5f
private const val BORDER_HALO = 5f
private const val CELL_DASH = 14f
private const val ARM = 0.44f
private const val GAP = 0.14f
private const val ZOOM_STEP = 2.0
