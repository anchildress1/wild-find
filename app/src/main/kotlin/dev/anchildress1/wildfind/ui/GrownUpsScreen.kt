package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.R
import dev.anchildress1.wildfind.ui.theme.Palette

private val INAT_FACTS = listOf(
    R.string.inat_asks,
    R.string.inat_ip,
    R.string.inat_terms,
    R.string.inat_account,
    R.string.inat_nothing,
)

/** Privacy facts, the iNaturalist disclosure, the hunting area, the opener replay (R1), and credits. */
@Composable
fun GrownUpsScreen(area: String?, onBack: () -> Unit, onArea: () -> Unit, onReplay: () -> Unit) {
    val context = LocalContext.current
    val version =
        remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Page(
        top = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(WildIcons.Back, stringResource(R.string.back), onBack)
                Text(
                    stringResource(R.string.grown_ups),
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        },
        bottom = { RuleLine() },
    ) {
        PaperCard(Modifier.rise(index = 0)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(WildIcons.Shield, contentDescription = null, Modifier.size(24.dp), tint = Palette.Forest)
                Text(
                    stringResource(R.string.privacy),
                    Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Fact(R.string.privacy_account)
            Fact(R.string.privacy_photos)
            Fact(R.string.privacy_location)
            Fact(R.string.privacy_network)
        }
        INaturalistCard(Modifier.rise(index = 1))
        Column(
            Modifier.rise(index = 2).fillMaxWidth().clip(CardShape).background(Palette.Paper)
                .border(1.5.dp, Palette.Line, CardShape),
        ) {
            Link(
                WildIcons.Map,
                stringResource(R.string.hunting_area),
                stringResource(R.string.hunting_area_detail, area ?: stringResource(R.string.region_near_you)),
                onArea,
            )
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(Palette.Line))
            Link(WildIcons.Replay, stringResource(R.string.replay_opener), null, onReplay)
        }
        Column(Modifier.rise(index = 3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.made_with), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.credits_models), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.credits_data), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.font_credits), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink2,
            )
        }
    }
}

@Composable
private fun INaturalistCard(modifier: Modifier) {
    PaperCard(modifier) {
        Text(stringResource(R.string.inat_title), style = MaterialTheme.typography.titleSmall)
        INAT_FACTS.forEach { Fact(it) }
    }
}

@Composable
private fun Fact(text: Int) {
    Row {
        Icon(WildIcons.Check, contentDescription = null, Modifier.size(20.dp), tint = Palette.Moss)
        Text(stringResource(text), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Link(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(
            min = 64.dp,
        ).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = Palette.Forest)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
            )
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink2) }
        }
        Icon(WildIcons.Chevron, contentDescription = null, Modifier.size(20.dp), tint = Palette.Ink)
    }
}
