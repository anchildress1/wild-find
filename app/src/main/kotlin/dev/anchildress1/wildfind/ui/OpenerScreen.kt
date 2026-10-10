package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.Palette

/** R1: the leave-it rule before anything else, Briar standing in the forest above its three steps. */
@Composable
fun OpenerScreen(replay: Boolean, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        // Ground shows until the painting decodes, so the page never flashes dark.
        assetImage(BACKGROUND)?.let {
            Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Title(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            // The page's own Column, bottom-aligned instead of top: Briar and the steps sit low, on the painting's
            // ground, and still scroll when 200% font makes them taller than the screen.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Briar(
                        BriarState.OPENER,
                        briarText(BriarState.OPENER),
                        Modifier.rise(index = 0).align(Alignment.CenterHorizontally).zIndex(1f),
                    )
                    RulePanel(Modifier.overlapUp(BRIAR_OVERLAP))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
                PrimaryButton(stringResource(if (replay) R.string.opener_done else R.string.opener_go), onDone)
            }
        }
    }
}

// No pill: a soft Paper glow that is solid under the words and fades to nothing at its edge, so the painting shows
// around the title.
@Composable
private fun Title(modifier: Modifier) {
    Row(
        modifier.drawBehind {
            // A circle squashed to the row's shape, so the glow fades out on all four sides.
            scale(scaleX = 1f, scaleY = size.height / size.width) {
                drawCircle(
                    Brush.radialGradient(
                        0f to Palette.Paper.copy(alpha = SCRIM),
                        GLOW_SOLID to Palette.Paper.copy(alpha = SCRIM),
                        1f to Color.Transparent,
                    ),
                    radius = size.width / 2,
                )
            }
        }.padding(horizontal = 44.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WildIcons.Sprout, contentDescription = null, Modifier.size(26.dp), tint = Palette.Forest)
        Text(
            stringResource(R.string.app_name),
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Forest,
        )
    }
}

@Composable
private fun RulePanel(modifier: Modifier) {
    val steps = listOf(
        WildIcons.Eye to stringResource(R.string.rule_look),
        WildIcons.Camera to stringResource(R.string.rule_photograph),
        WildIcons.Sprout to stringResource(R.string.rule_leave),
    )
    Column(
        modifier.fillMaxWidth().background(Palette.Paper.copy(alpha = SCRIM), CardShape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp + BRIAR_OVERLAP, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.opener_intro),
            Modifier.rise(index = 1).semantics { heading() },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Palette.Ink,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val style = fitted(steps.map { it.second }, maxWidth - ICON - GAP)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                steps.forEachIndexed { i, (icon, text) -> RuleStep(icon, text, style, Modifier.rise(index = i + 2)) }
            }
        }
    }
}

// One size for all three steps, shrunk until the longest fits on one line; it stops at MIN_STEP_SCALE so a large
// font setting wraps instead of shrinking the text it asked to grow.
@Composable
private fun fitted(texts: List<String>, width: Dp): TextStyle {
    val base = MaterialTheme.typography.headlineMedium.copy(fontSize = STEP_SP.sp, lineHeight = STEP_LINE_SP.sp)
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { width.roundToPx() }
    return remember(texts, available, base) {
        val longest = texts.maxOf { measurer.measure(it, base, maxLines = 1, softWrap = false).size.width }
        val scale = (available.toFloat() / longest).coerceIn(MIN_STEP_SCALE, 1f)
        base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
    }
}

@Composable
private fun RuleStep(icon: ImageVector, text: String, style: TextStyle, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(ICON).background(Palette.Forest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(26.dp), tint = Palette.Paper)
        }
        Text(text, style = style, color = Palette.Ink)
    }
}

// Lays the panel [by] higher and reports it that much shorter, so Briar's leaf band overlaps its top edge.
private fun Modifier.overlapUp(by: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

private const val BACKGROUND = "opener_background.webp"

// Measured on opener_background.webp: Paper at this alpha holds Ink at 6.96:1 even over pure black, the darkest pixel
// under the panel, and Forest at 5.65:1 over the darkest pixel behind the title.
private const val SCRIM = 0.7f

// The title glow stays at full scrim out to this share of its radius, past the ends of the words.
private const val GLOW_SOLID = 0.7f

private const val STEP_SP = 30
private const val STEP_LINE_SP = 34
private const val MIN_STEP_SCALE = 0.75f
private val ICON = 48.dp
private val GAP = 14.dp
private val BRIAR_OVERLAP = 24.dp
