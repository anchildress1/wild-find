package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.game.Stop
import dev.anchildress1.wildfind.ui.theme.Palette
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The hunt list: a trail of numbered stops, each showing its name and plant type from the start, a check and a star
 * once found; tapping a stop opens the camera on it.
 *
 * @param area the hunting area's label, or null until the offline names load
 * @param offline the list came from the cache because iNat didn't answer
 */
@Composable
@Suppress("LongParameterList")
fun HuntScreen(
    stops: List<Stop>,
    area: String?,
    offline: Boolean,
    onStop: (Int) -> Unit,
    onFinish: () -> Unit,
    onGrownUps: () -> Unit,
) {
    Page(
        bottom = {
            stops.firstOrNull { !it.found }?.let {
                PrimaryButton(stringResource(R.string.start_looking), { onStop(it.row) })
            }
            RuleLine()
        },
    ) {
        Header(stops.size, area, onGrownUps)
        if (offline) OfflineBanner()
        Trail(stops, onStop)
        // Below the trail, not pinned: ending early is the rare path, and the pinned area stays short.
        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(
                stringResource(R.string.finish_hunt),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Forest,
            )
        }
    }
}

@Composable
private fun Header(count: Int, area: String?, onGrownUps: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.hunt_title, count, count),
                Modifier.weight(1f),
                style = MaterialTheme.typography.displayMedium,
            )
            RoundIconButton(WildIcons.More, stringResource(R.string.grown_ups), onGrownUps)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val month = LocalDate.now().month.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale)
            Text(
                stringResource(R.string.hunt_where, area ?: stringResource(R.string.region_near_you), month),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Ink2,
            )
            val label = stringResource(R.string.difficulty_label)
            Text(
                stringResource(R.string.difficulty_low),
                Modifier.semantics { contentDescription = label }
                    .border(1.5.dp, Palette.Moss, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Forest,
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().rise().background(Palette.Paper, RoundedCornerShape(16.dp))
            .border(1.5.dp, Palette.Line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WildIcons.NoSignal, contentDescription = null, Modifier.size(24.dp), tint = Palette.Forest)
        Text(
            stringResource(R.string.offline_banner),
            Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
        )
    }
}

// Stops zigzag down the page with the dashed trail drawn through their tiles; growing text pushes them down, so
// nothing overlaps at 200% font scale.
@Composable
private fun Trail(stops: List<Stop>, onStop: (Int) -> Unit) {
    val centers = remember { mutableStateMapOf<Int, Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(TRAIL_DASH, TRAIL_GAP)) }
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
            val points = stops.indices.mapNotNull(centers::get).map { it - origin }
            if (points.size < 2) return@drawBehind
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.zipWithNext { a, b ->
                    cubicTo(b.x, a.y, a.x, b.y, b.x, b.y)
                }
            }
            drawPath(path, Palette.Moss, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
        },
    ) {
        stops.forEachIndexed { index, stop ->
            val alignment = if (index % 2 == 0) Alignment.Start else Alignment.End
            val last = index == stops.lastIndex
            Row(
                Modifier.fillMaxWidth().rise(index),
                horizontalArrangement = if (last) Arrangement.SpaceBetween else Arrangement.Start,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (last) Briar(BriarState.WELCOME, briarText(BriarState.WELCOME), Modifier.weight(1f, fill = false))
                Box(Modifier.weight(1f, fill = !last), contentAlignment = alignmentFor(alignment)) {
                    StopCard(index + 1, stop, { onStop(stop.row) }) { centers[index] = it }
                }
            }
        }
    }
}

private fun alignmentFor(horizontal: Alignment.Horizontal) =
    if (horizontal == Alignment.Start) Alignment.TopStart else Alignment.TopEnd

@Composable
private fun StopCard(number: Int, stop: Stop, onClick: () -> Unit, onCenter: (Offset) -> Unit) {
    val type = typeLabel(stop.type)
    val description = when {
        type == null -> stringResource(R.string.stop_plain, number, stop.name)
        stop.found -> stringResource(R.string.stop_found, number, stop.name, type)
        else -> stringResource(R.string.stop_open, number, stop.name, type)
    }
    Column(
        Modifier.width(STOP_WIDTH).clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
        }.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.padding(12.dp).container(stopKey(stop.row)).onGloballyPositioned { tile ->
                onCenter(tile.positionInRoot() + Offset(tile.size.width / 2f, tile.size.height / 2f))
            },
        ) {
            TypeTile(stop.type, TILE)
            Badge(number, stop.found, Modifier.align(Alignment.TopStart).offset(-BADGE_NUDGE, -BADGE_NUDGE))
            if (stop.found) Star(34.dp, Modifier.align(Alignment.BottomEnd).offset(12.dp, 10.dp).pop())
        }
        Column(
            Modifier.background(Palette.Paper, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stop.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            type?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2) }
        }
    }
}

@Composable
private fun Badge(number: Int, found: Boolean, modifier: Modifier) {
    Box(modifier.size(36.dp).background(Palette.Forest, CircleShape), contentAlignment = Alignment.Center) {
        if (found) {
            Icon(WildIcons.Check, contentDescription = null, Modifier.size(20.dp), tint = Color.White)
        } else {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

private val TILE = 104.dp
private val BADGE_NUDGE = 12.dp
private val STOP_WIDTH = 150.dp
private const val TRAIL_DASH = 12f
private const val TRAIL_GAP = 26f
