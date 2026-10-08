package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.ui.theme.Palette

/** R1: the leave-it rule before anything else; the opener art is pending, so a placeholder card holds its place. */
@Composable
fun OpenerScreen(replay: Boolean, onDone: () -> Unit) {
    Page(
        bottom = {
            PrimaryButton(stringResource(if (replay) R.string.opener_done else R.string.opener_go), onDone)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(WildIcons.Sprout, contentDescription = null, Modifier.size(26.dp), tint = Palette.Forest)
            Text(
                stringResource(R.string.app_name),
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Forest,
            )
        }
        ArtPlaceholder(Modifier.rise(index = 0))
        Text(
            stringResource(R.string.opener_intro),
            Modifier.rise(index = 1),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Ink2,
        )
        RuleStep(WildIcons.Eye, stringResource(R.string.rule_look), Modifier.rise(index = 2))
        RuleStep(WildIcons.Camera, stringResource(R.string.rule_photograph), Modifier.rise(index = 3))
        RuleStep(WildIcons.Sprout, stringResource(R.string.rule_leave), Modifier.rise(index = 4))
    }
}

@Composable
private fun ArtPlaceholder(modifier: Modifier) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))
    Box(
        modifier.fillMaxWidth().heightIn(min = 220.dp)
            .background(Palette.Paper, RoundedCornerShape(28.dp))
            .drawBehind {
                drawRoundRect(
                    Palette.Moss,
                    cornerRadius = CornerRadius(28.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
                )
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.opener_art),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Ink2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RuleStep(icon: ImageVector, text: String, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(Palette.Forest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(26.dp), tint = Palette.Paper)
        }
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 34.sp),
            color = Palette.Ink,
        )
    }
}

private const val DASH = 18f
