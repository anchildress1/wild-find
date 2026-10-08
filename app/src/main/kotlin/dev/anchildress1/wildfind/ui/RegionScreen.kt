package dev.anchildress1.wildfind.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.ui.theme.Palette

/**
 * Where the kid hunts: the rough location (coarse permission only, rounded to whole degrees) or the built-in map.
 * No place is ever named (R2, R7).
 *
 * @param locating waiting for the rough location
 * @param denied location was denied once, so it is never asked again and only the map remains
 */
@Composable
fun RegionScreen(locating: Boolean, denied: Boolean, onLocation: (granted: Boolean) -> Unit, onMap: () -> Unit) {
    val ask = rememberLocation(onLocation)
    Page(
        bottom = {
            if (!denied) {
                PrimaryButton(
                    stringResource(if (locating) R.string.region_locating else R.string.region_use_location),
                    ask,
                    icon = WildIcons.Pin,
                    enabled = !locating,
                )
            }
            OutlineButton(stringResource(R.string.region_pick_map), onMap, icon = WildIcons.Map)
            PrivateLine(stringResource(R.string.region_private))
        },
    ) {
        Box(
            Modifier.padding(top = 12.dp).size(96.dp).background(Palette.Paper, CircleShape)
                .border(1.5.dp, Palette.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(WildIcons.Pin, contentDescription = null, Modifier.size(48.dp), tint = Palette.Forest) }
        Text(stringResource(R.string.region_title), style = MaterialTheme.typography.displayMedium, color = Palette.Ink)
        Text(stringResource(R.string.region_body), style = MaterialTheme.typography.bodyLarge, color = Palette.Ink2)
    }
}

/** Asks for coarse location once, or answers at once when it was already granted. */
@Composable
fun rememberLocation(onAnswer: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onAnswer)
    return {
        if (context.checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onAnswer(true)
        } else {
            launcher.launch(LOCATION)
        }
    }
}

/** A privacy note with its shield. */
@Composable
fun PrivateLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(WildIcons.Shield, contentDescription = null, Modifier.size(20.dp), tint = Palette.Ink2)
        Text(
            text,
            Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink2,
        )
    }
}

private const val LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
