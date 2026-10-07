package dev.anchildress1.wildfind

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.download.GemmaDownloadService
import dev.anchildress1.wildfind.ui.BriarSprite

/** Single-activity host for the Compose UI. */
class MainActivity : ComponentActivity() {
    // Every time the app comes to the front, not just on create: returning from recents must restart a download
    // stuck in backoff. The download never blocks the UI; notifications only show its progress.
    override fun onStart() {
        super.onStart()
        GemmaDownloadService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BriarSprite("idle", stringResource(R.string.briar_description))
                    Text(stringResource(R.string.leave_it_rule), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}
