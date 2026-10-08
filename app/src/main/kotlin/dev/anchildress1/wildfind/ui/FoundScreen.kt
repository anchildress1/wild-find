package dev.anchildress1.wildfind.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.sprite.BriarState
import dev.anchildress1.wildfind.ui.theme.LocalReducedMotion
import dev.anchildress1.wildfind.ui.theme.Motion
import dev.anchildress1.wildfind.ui.theme.Palette

/** "Found it!" with the captured reticle crop in a medallion; the crop lives in memory only and is never written. */
@Composable
fun FoundScreen(info: FoundInfo, crop: Pixels?, onNext: () -> Unit, onHunt: () -> Unit) {
    Page(
        bottom = {
            RuleLine()
            PrimaryButton(nextLabel(info), onNext)
            if (!info.tutorial && info.next != null) {
                TextButton(onHunt, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(
                        stringResource(R.string.back_to_hunt),
                        style = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline),
                        color = Palette.Forest,
                    )
                }
            }
        },
    ) {
        if (!info.tutorial) Dots(info.found, info.total)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.found_title), style = MaterialTheme.typography.displayLarge)
            Text(
                info.name,
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.Forest,
                textAlign = TextAlign.Center,
            )
            info.description?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink2, textAlign = TextAlign.Center)
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
            // Briar first, so his cheering arms never cover the photo or the star.
            Briar(BriarState.FOUND, briarText(BriarState.FOUND), Modifier.align(Alignment.BottomStart))
            Medallion(crop, info.name, Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 10.dp))
            // Every win looks the same, the grass practice included.
            val star = stringResource(R.string.one_star)
            Star(76.dp, Modifier.align(Alignment.TopEnd).semantics { contentDescription = star }.pop(index = 2))
        }
        Column(Modifier.fillMaxWidth().rise(index = 3), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(countdown(info), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(stringResource(R.string.plus_star), style = MaterialTheme.typography.bodyLarge, color = Palette.Ink2)
        }
    }
}

@Composable
private fun nextLabel(info: FoundInfo): String = when {
    info.tutorial -> stringResource(R.string.see_finds)
    info.next == null -> stringResource(R.string.see_stars)
    else -> stringResource(R.string.next_target, info.next)
}

@Composable
private fun countdown(info: FoundInfo): String {
    val left = info.total - info.found
    return when {
        info.tutorial -> stringResource(R.string.now_find)
        left == 0 -> stringResource(R.string.all_found)
        else -> pluralStringResource(R.plurals.left_to_find, left, left)
    }
}

@Composable
private fun Dots(found: Int, total: Int) {
    val label = pluralStringResource(R.plurals.found_progress, total, found, total)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val dot = Modifier.size(12.dp)
            Box(
                if (i <
                    found
                ) {
                    dot.background(Palette.Forest, CircleShape)
                } else {
                    dot.border(2.dp, Palette.Moss, CircleShape)
                },
            )
        }
        Text(
            stringResource(R.string.camera_progress, found, total),
            Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink2,
        )
    }
}

// The lift: the medallion grows in over Motion.BIG; under reduced motion it is simply there.
@Composable
private fun Medallion(crop: Pixels?, name: String, modifier: Modifier) {
    val image: ImageBitmap? = remember(crop) { crop?.let(::bitmap) }
    val reduced = LocalReducedMotion.current
    val lift = remember { Animatable(if (reduced) 1f else LIFT_FROM) }
    LaunchedEffect(Unit) { if (!reduced) lift.animateTo(1f, tween(Motion.BIG, easing = Motion.Overshoot)) }
    Box(
        modifier.graphicsLayer {
            scaleX = lift.value
            scaleY = lift.value
        }.size(MEDALLION).border(3.dp, Palette.Wheat, CircleShape).padding(3.dp)
            .border(6.dp, Palette.Paper, CircleShape).clip(CircleShape).background(Palette.Night),
    ) {
        image?.let {
            Image(
                it,
                stringResource(R.string.found_photo, name),
                Modifier.size(MEDALLION),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun bitmap(pixels: Pixels): ImageBitmap =
    Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888).asImageBitmap()

private val MEDALLION = 210.dp
private const val LIFT_FROM = 0.6f
