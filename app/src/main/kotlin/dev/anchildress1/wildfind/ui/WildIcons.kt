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
import dev.anchildress1.wildfind.core.hunt.PlantType
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

    private val types = PlantType.entries.associateWith(::draw)

    /** The illustration for [type]; the spec draws one per type, never per species. */
    fun of(type: PlantType): ImageVector = types.getValue(type)

    private fun draw(type: PlantType): ImageVector = when (type) {
        PlantType.TREE -> line("tree", circle(12f, 9f, 6f), "M12 15v6", "M12 18l-2.5-1.8", "M8 21h8", width = 1.8f)

        PlantType.HERB -> line("herb", "M5 19C5 10 10 5 19 5c0 9-5 14-14 14z", "M5 19l8-8", width = 1.8f)

        PlantType.FERN -> line("fern", "M12 21V4", FERN_FRONDS, width = 1.8f)

        PlantType.GRASS -> GRASS

        PlantType.VINE -> line(
            "vine",
            "M4 20c5 0 6-4 8-7s4-6 8-6",
            "M9 15.5c-1.8-.3-3-1.6-3-3.5 1.8.2 3 1.5 3 3.5z",
            "M15 9.5c.3-1.8 1.6-3 3.5-3-.2 1.8-1.5 3-3.5 3z",
            "M20 7c1 1 1 2.5 0 3",
            width = 1.8f,
        )

        PlantType.SHRUB -> line(
            "shrub",
            "M3 21h18",
            "M6 18a3.5 3.5 0 0 1 .5-6.9A4.5 4.5 0 0 1 15 9.5a3.5 3.5 0 0 1 3 6.5 2.5 2.5 0 0 1-1 2z",
            "M10 18v3M14 18v3",
            width = 1.8f,
        )

        PlantType.MOSS -> line(
            "moss",
            "M3 19h18",
            "M4 19c0-2 1.5-3.2 3-3.2s3 1.2 3 3.2",
            "M10 19c0-2.6 2-4 4-4s4 1.4 4 4",
            "M7 15.8V12M14 15v-4.5M17.5 16.5V13",
            circle(7f, 11f, 1f),
            circle(14f, 9.5f, 1f),
            circle(17.5f, 12f, 1f),
            width = 1.8f,
        )

        PlantType.CONIFER -> line("conifer", "M12 3l-5 7h3l-4 6h12l-4-6h3z", "M12 16v5", width = 1.8f)
    }

    /** The grass tutorial's illustration. */
    val Grass: ImageVector get() = GRASS
}

private const val STAR = "M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L3.6 9.6l5.8-.8z"
private const val CORNERS = "M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4"
private const val FERN_FRONDS =
    "M12 7l-3-2M12 7l3-2M12 10.5l-4-2.2M12 10.5l4-2.2M12 14l-4.5-2.2M12 14l4.5-2.2M12 17.5l-4-1.8M12 17.5l4-1.8"

private val GRASS = line(
    "grass",
    "M4 21h16",
    "M7 21c0-5 .8-9 2.5-12",
    "M12 21c0-6 0-10-.5-15",
    "M17 21c0-4.5-1.2-8-4-10.5",
    width = 1.8f,
)

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
