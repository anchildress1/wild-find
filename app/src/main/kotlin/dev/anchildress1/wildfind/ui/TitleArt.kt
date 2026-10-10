package dev.anchildress1.wildfind.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.anchildress1.wildfind.R

/**
 * The owner's leafy "Wild Find" title from `assets/title.webp`. Its box is reserved before the picture decodes, so the
 * page never jumps, and TalkBack reads the name it replaces.
 */
@Composable
fun TitleArt(modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name)
    Box(modifier.aspectRatio(TITLE_ASPECT).semantics { contentDescription = name }) {
        assetImage("title.webp")?.let { Image(it, contentDescription = null, Modifier.fillMaxSize()) }
    }
}

// title.webp is 900 x 755.
private const val TITLE_ASPECT = 900f / 755f
