package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
 * @param background the page color; transparent lets art drawn behind the page show through
 * @param top a fixed row above the scrolling content
 * @param centered centers [content] vertically when it is shorter than the screen; it still scrolls when taller
 */
@Composable
@Suppress("LongParameterList")
fun Page(
    modifier: Modifier = Modifier,
    background: Color = Palette.Ground,
    top: @Composable () -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit = {},
    centered: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().background(background).safeDrawingPadding().padding(horizontal = 20.dp),
    ) {
        top()
        if (centered) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Scrolled content has no height limit to center in, so it gets at least the viewport's height.
                val viewport = maxHeight
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    content = content,
                )
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
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
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Forest,
            contentColor = Color.White,
            disabledContainerColor = Palette.Husk,
            disabledContentColor = Palette.Ink2,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        icon?.let { Icon(it, contentDescription = null, Modifier.padding(start = 10.dp).size(22.dp)) }
    }
}

/** The 56 dp outlined button with an optional leading [icon]. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        border = BorderStroke(2.dp, Palette.Forest),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Forest),
    ) {
        icon?.let { Icon(it, contentDescription = null, Modifier.padding(end = 10.dp).size(22.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** The leave-it rule, on every screen a kid hunts from; the sprout rides inline so a wrapped line stays centered. */
@Composable
fun RuleLine(modifier: Modifier = Modifier) {
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
            Icon(WildIcons.Sprout, contentDescription = null, tint = Palette.Forest)
        },
    )
    Text(
        text,
        // The inline sprout's placeholder character would be read aloud; TalkBack gets the rule alone.
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = rule },
        color = Palette.Forest,
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
 * A plant type's picture standing on its square's bottom edge, with no box around it; no type shows the untyped
 * picture.
 *
 * @param size the square's side
 */
@Composable
fun TypeTile(type: PlantType?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) { PlantArt(type, Modifier.size(size)) }
}

/** A find star, the painted art from `assets/star.webp`. */
@Composable
fun Star(size: Dp, modifier: Modifier = Modifier) {
    val star = assetImage("star.webp")
    if (star == null) Spacer(modifier.size(size)) else Image(star, contentDescription = null, modifier.size(size))
}

/**
 * The line that tells a kid what to look for: the build's description, else the plant type in sentence case ("A tree");
 * nothing when the plant has neither.
 */
@Composable
fun PlantLine(
    description: String?,
    type: PlantType?,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val line = description ?: type?.let { typeLabel(it).replaceFirstChar(Char::titlecase) } ?: return
    Text(line, modifier, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = textAlign)
}

/** "a tree", "an herb": the type as it reads mid-sentence. */
@Composable
fun typeLabel(type: PlantType): String = stringResource(
    when (type) {
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

private const val SPROUT = "sprout"
