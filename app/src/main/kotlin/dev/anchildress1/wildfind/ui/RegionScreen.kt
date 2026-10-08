package dev.anchildress1.wildfind.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.ui.theme.Palette

/** The region the game was built and tested in, PRD key 34_-85. */
val WEST_GEORGIA = RegionKey(34, -85)

/** The kid-facing name of [region]. */
@Composable
fun regionName(region: RegionKey?): String =
    stringResource(if (region == WEST_GEORGIA) R.string.region_west_georgia else R.string.region_near_you)

/**
 * Where the kid hunts: coarse location only, asked once per tap and never again after a denial; the manual pick is
 * always there (R2, R7).
 *
 * @param locating waiting for the rough location
 * @param failed location was denied or unavailable, so only the manual pick shows
 */
@Composable
fun RegionScreen(
    locating: Boolean,
    failed: Boolean,
    onLocation: (granted: Boolean) -> Unit,
    onPick: (RegionKey) -> Unit,
    onElsewhere: () -> Unit,
) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onLocation)
    val granted = { context.checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED }
    var choice by rememberSaveable { mutableIntStateOf(0) }
    Page(
        bottom = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(WildIcons.Shield, contentDescription = null, Modifier.size(20.dp), tint = Palette.Ink2)
                Text(
                    stringResource(R.string.region_private),
                    Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Ink2,
                )
            }
            OutlineButton(stringResource(R.string.region_next), {
                if (choice ==
                    0
                ) {
                    onPick(WEST_GEORGIA)
                } else {
                    onElsewhere()
                }
            })
        },
    ) {
        Box(
            Modifier.padding(top = 12.dp).size(96.dp).background(Palette.Paper, CircleShape)
                .border(1.5.dp, Palette.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(WildIcons.Pin, contentDescription = null, Modifier.size(48.dp), tint = Palette.Forest) }
        Text(stringResource(R.string.region_title), style = MaterialTheme.typography.displayMedium, color = Palette.Ink)
        Text(stringResource(R.string.region_body), style = MaterialTheme.typography.bodyLarge, color = Palette.Ink2)
        if (failed) {
            Text(
                stringResource(R.string.region_location_failed),
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Ink,
            )
        } else {
            LocationButton(locating) { if (granted()) onLocation(true) else ask.launch(LOCATION) }
        }
        Divider()
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice(
                stringResource(R.string.region_west_georgia),
                stringResource(R.string.region_west_georgia_detail),
                choice == 0,
            ) { choice = 0 }
            Choice(
                stringResource(R.string.region_elsewhere),
                stringResource(R.string.region_elsewhere_detail),
                choice == 1,
            ) { choice = 1 }
        }
    }
}

@Composable
private fun LocationButton(locating: Boolean, onClick: () -> Unit) {
    PrimaryButton(
        stringResource(if (locating) R.string.region_locating else R.string.region_use_location),
        onClick,
        icon = WildIcons.Pin,
        enabled = !locating,
    )
}

@Composable
private fun Divider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).height(1.5.dp).background(Palette.Line))
        Text(stringResource(R.string.region_or), style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2)
        Box(Modifier.weight(1f).height(1.5.dp).background(Palette.Line))
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .background(Palette.Paper, CardShape)
            .border(if (selected) 2.5.dp else 1.5.dp, if (selected) Palette.Forest else Palette.Line, CardShape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Forest))
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                ),
                color = Palette.Ink,
            )
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2)
        }
    }
}

private const val LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
