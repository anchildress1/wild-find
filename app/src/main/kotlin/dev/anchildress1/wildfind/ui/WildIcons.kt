// Path coordinates are the design spec's 24-unit icon geometry, copied as data.
@file:Suppress("MagicNumber")

package dev.anchildress1.wildfind.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import dev.anchildress1.wildfind.ui.theme.Palette

/** The design spec's line icons on a 24-unit grid; `Icon` tints them with the content color. */
object WildIcons {
    /** Back arrow. */
    val Back = line("back", "M19 12H5", "M11 6l-6 6 6 6")

    /** Forward arrow on primary buttons. */
    val Forward = line("forward", "M5 12h14", "M13 6l6 6-6 6")

    /** Row chevron. */
    val Chevron = line("chevron", "M9 5l7 7-7 7")

    /** Arrow pad: north. */
    val Up = line("up", "M6 15l6-6 6 6", width = 2.4f)

    /** Arrow pad: south. */
    val Down = line("down", "M6 9l6 6 6-6", width = 2.4f)

    /** Arrow pad: west. */
    val Left = line("left", "M15 6l-6 6 6 6", width = 2.4f)

    /** Arrow pad: east. */
    val Right = line("right", "M9 6l6 6-6 6", width = 2.4f)

    /** Zoom in. */
    val Plus = line("plus", "M12 5v14M5 12h14", width = 2.2f)

    /** Zoom out. */
    val Minus = line("minus", "M5 12h14", width = 2.2f)

    /** Go to my area. */
    val Locate = line("locate", circle(12f, 12f, 6f), circle(12f, 12f, 1.5f), "M12 2v4M12 18v4M2 12h4M18 12h4")

    /** Pick on a map. */
    val Map = line("map", "M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2z", "M9 4v14M15 6v14")

    /** Found check. */
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5", width = 3f)

    /** The overflow menu that opens the grown-ups page. */
    val More = fill("more", circle(12f, 5f, 2f), circle(12f, 12f, 2f), circle(12f, 19f, 2f))

    /** The leave-it rule's sprout. */
    val Sprout = line(
        "sprout",
        "M12 21v-9",
        "M12 12c0-4 2.5-6.5 7-6.5 0 4-2.5 6.5-7 6.5z",
        "M12 14c0-3.2-2-5.2-6-5.2 0 3.2 2 5.2 6 5.2z",
    )

    /** "Look." */
    val Eye = line("eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))

    /** "Photograph." and the Capture button. */
    val Camera = line(
        "camera",
        "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z",
        circle(12f, 13.5f, 3.5f),
    )

    /** The hazard card's warning sign. */
    val Warning = line("warning", "M12 3L2 20h20L12 3z", "M12 10v4.5", circle(12f, 17.2f, 0.6f))

    /** Location. */
    val Pin = line("pin", "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", circle(12f, 9.5f, 2.5f))

    /** No signal. */
    val NoSignal = line(
        "no-signal",
        "M3 3l18 18",
        "M5 12.5a10 10 0 0 1 4-2.4M19 12.5a10 10 0 0 0-3.2-2.1",
        "M8.5 16a5 5 0 0 1 7 0",
        circle(12f, 19f, 0.8f),
    )

    /** Privacy. */
    val Shield = line("shield", "M12 3l7 3v5c0 5-3 8.5-7 10-4-1.5-7-5-7-10V6z")

    /** Replay the opener. */
    val Replay = line("replay", "M4 12a8 8 0 1 0 2.3-5.6", "M4 4v4h4")

    /** "Keep looking for…" */
    val Search = line("search", circle(11f, 11f, 6.5f), "M16 16l4.5 4.5")

    /** "Put the plant in the circle" */
    val Target = line("target", circle(12f, 12f, 8f), circle(12f, 12f, 2.5f))

    /** "Point the camera at a plant" */
    val Aim = line("aim", CORNERS, "M8.5 15.5c0-4.5 2.5-7 7-7 0 4.5-2.5 7-7 7z")

    /** "Tap the plant to focus" */
    val Focus = line("focus", CORNERS, circle(12f, 12f, 2f))

    /** "Get closer or zoom in" */
    val Steps = fill(
        "steps",
        ellipse(8.5f, 7f, 2.6f, 4f),
        ellipse(8.5f, 14.5f, 1.8f, 1.4f),
        ellipse(15.5f, 11f, 2.6f, 4f),
        ellipse(15.5f, 18.5f, 1.8f, 1.4f),
    )

    /** "Checking…" */
    val Dots = fill("dots", circle(6f, 12f, 2f), circle(12f, 12f, 2f), circle(18f, 12f, 2f))

    /** A find star: wheat, outlined in ink, never tinted. */
    val Star: ImageVector = ImageVector.Builder("star", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(STAR),
        fill = SolidColor(Palette.Wheat),
        stroke = SolidColor(Palette.Ink),
        strokeLineWidth = 1.2f,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    /** An empty star slot. */
    val StarOutline = line("star-outline", STAR, width = 1.4f)
}

private const val STAR = "M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L3.6 9.6l5.8-.8z"
private const val CORNERS = "M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4"
private fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
    "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0z"

private fun line(name: String, vararg paths: String, width: Float = 2f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun fill(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()
