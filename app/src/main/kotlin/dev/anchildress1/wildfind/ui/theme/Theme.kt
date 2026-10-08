package dev.anchildress1.wildfind.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.anchildress1.wildfind.R

/** Palette from the design spec's foundations, pulled from Briar's concept board. */
object Palette {
    /** App background. */
    val Ground = Color(0xFFF4EDE1)

    /** Cards and sheets. */
    val Paper = Color(0xFFFBF7F0)

    /** Text, 13.4:1 on Ground. */
    val Ink = Color(0xFF1D261C)

    /** Secondary text, 6.8:1. */
    val Ink2 = Color(0xFF4A5446)

    /** Primary buttons; white on it is 9.8:1. */
    val Forest = Color(0xFF2F4A2E)

    /** Icons and outlines. */
    val Moss = Color(0xFF5E6B36)

    /** Stars, always outlined in Ink. */
    val Wheat = Color(0xFFD9A441)

    /** Hairlines and quiet borders. */
    val Line = Color(0xFFCFC6AE)

    /** Pressed and checking fills. */
    val Husk = Color(0xFFE9E2CC)

    /** The hazard card only; white on it is 8.3:1. */
    val Hazard = Color(0xFF8C2F12)

    /** The hazard ring on the camera. */
    val HazardRing = Color(0xFFF2B79A)

    /** Camera overlays, under white text. */
    val Scrim = Color(0xCC141B13)

    /** The camera's backdrop before the preview arrives. */
    val Night = Color(0xFF2C3B2A)
}

/** Motion durations from the spec, in milliseconds. */
object Motion {
    /** Pill text and chips. */
    const val QUICK = 150

    /** Screen enter and card to camera. */
    const val MOVE = 300

    /** Found lift and stars pop. */
    const val BIG = 500

    /** Delay between popping stars. */
    const val STAGGER = 120

    /** Material emphasized decelerate. */
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** A small overshoot for pops. */
    val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
}

/** True when the system animator scale is 0: crossfades only, and Briar holds frame 0. */
val LocalReducedMotion = staticCompositionLocalOf { false }

private val fredoka = FontFamily(
    Font(R.font.fredoka, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.fredoka, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val atkinson = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

// Nothing under 16 sp; display in Fredoka, everything a kid reads at length in Atkinson Hyperlegible.
private val typography = Typography(
    displayLarge = TextStyle(fontFamily = fredoka, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 50.sp),
    displayMedium = TextStyle(
        fontFamily = fredoka,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = fredoka,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = fredoka,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = fredoka,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(fontFamily = atkinson, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = atkinson, fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = atkinson, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(fontFamily = atkinson, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = atkinson, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
)

private val colors = lightColorScheme(
    primary = Palette.Forest,
    onPrimary = Color.White,
    background = Palette.Ground,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    onSurfaceVariant = Palette.Ink2,
    outline = Palette.Line,
    error = Palette.Hazard,
)

/** wild-find's theme: one light scheme tuned for sunlight, the spec's type scale, and reduced-motion awareness. */
@Composable
fun WildFindTheme(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val reduced = remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
    }
}
