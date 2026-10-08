package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.hunt.PlantType
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.Palette

/** Card corner radius. */
val CardShape = RoundedCornerShape(20.dp)

/**
 * A screen on Ground: [content] scrolls, so every layout holds at 200% font scale, and [bottom] stays pinned.
 *
 * @param top a fixed row above the scrolling content
 */
@Composable
fun Page(
    modifier: Modifier = Modifier,
    top: @Composable () -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().background(Palette.Ground).safeDrawingPadding().padding(horizontal = 20.dp),
    ) {
        top()
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = bottom,
        )
    }
}

/** The 56 dp Forest button with an optional trailing [icon]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = WildIcons.Forward,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Forest, contentColor = Color.White),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        icon?.let { Icon(it, contentDescription = null, Modifier.padding(start = 10.dp).size(22.dp)) }
    }
}

/** The 56 dp outlined button. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        border = BorderStroke(2.dp, Palette.Forest),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Forest),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** The leave-it rule, on every screen a kid hunts from; the sprout rides inline so a wrapped line stays centered. */
@Composable
fun RuleLine(modifier: Modifier = Modifier, color: Color = Palette.Forest) {
    val rule = stringResource(R.string.leave_it_rule)
    val text = remember(rule) {
        buildAnnotatedString {
            appendInlineContent(SPROUT)
            append(" ")
            append(rule)
        }
    }
    val sprout = mapOf(
        SPROUT to InlineTextContent(Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(WildIcons.Sprout, contentDescription = null, tint = color)
        },
    )
    Text(
        text,
        modifier.fillMaxWidth(),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        inlineContent = sprout,
    )
}

/** A 48 dp icon button. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Palette.Ink) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, Modifier.size(24.dp), tint = tint)
    }
}

/** A Paper card with a hairline border. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Palette.Paper, CardShape).border(1.5.dp, Palette.Line, CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * A plant type's tile; found tiles get a Forest border. No type means no tile (spec: name only).
 *
 * @param size the tile's side
 */
@Composable
fun TypeTile(type: PlantType?, found: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * TILE_RADIUS)
    Box(
        modifier.size(size).background(Palette.Paper, shape)
            .border(if (found) 2.5.dp else 1.5.dp, if (found) Palette.Forest else Palette.Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        type?.let {
            Icon(WildIcons.of(it), contentDescription = null, Modifier.size(size * ICON_SHARE), Palette.Forest)
        }
    }
}

/** A find star. */
@Composable
fun Star(size: Dp, modifier: Modifier = Modifier) {
    Image(WildIcons.Star, contentDescription = null, modifier.size(size))
}

/** "a tree", "an herb", or null for no type. */
@Composable
fun typeLabel(type: PlantType?): String? = type?.let {
    stringResource(
        when (it) {
            PlantType.TREE -> R.string.type_tree
            PlantType.SHRUB -> R.string.type_shrub
            PlantType.VINE -> R.string.type_vine
            PlantType.HERB -> R.string.type_herb
            PlantType.GRASS -> R.string.type_grass
            PlantType.FERN -> R.string.type_fern
            PlantType.MOSS -> R.string.type_moss
            PlantType.CONIFER -> R.string.type_conifer
        },
    )
}

private const val SPROUT = "sprout"
private const val TILE_RADIUS = 0.27f
private const val ICON_SHARE = 0.6f
