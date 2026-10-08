package dev.anchildress1.wildfind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.anchildress1.wildfind.ui.GameRoute
import dev.anchildress1.wildfind.ui.theme.WildFindTheme

/** Single-activity host for the Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { WildFindTheme { GameRoute() } }
    }
}
