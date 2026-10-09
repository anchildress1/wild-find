package dev.anchildress1.wildfind.core.map

import dev.anchildress1.wildfind.core.region.RegionKey
import kotlin.math.abs

/** A 1° step of the map's arrow pad, the TalkBack path that needs no dragging. */
enum class Heading(internal val lat: Int, internal val lng: Int) {
    /** Up. */
    NORTH(1, 0),

    /** Down. */
    SOUTH(-1, 0),

    /** Right. */
    EAST(0, 1),

    /** Left. */
    WEST(0, -1),
}

/**
 * Where the map picker looks: an equirectangular view centered on the fixed crosshairs.
 *
 * @property lat center latitude, clamped to ±90
 * @property lng center longitude, wrapped into [-180, 180)
 * @property span degrees of longitude across the view's width; the same scale runs vertically
 */
data class MapCamera(val lat: Double = 0.0, val lng: Double = 0.0, val span: Double = WORLD_SPAN) {
    init {
        require(lat in -MAX_LAT..MAX_LAT) { "latitude $lat" }
        require(lng >= -HALF_TURN && lng < HALF_TURN) { "longitude $lng" }
        require(span in MIN_SPAN..WORLD_SPAN) { "span $span" }
    }

    /** "Hunt here" unlocks once the view is about [PICK_SPAN] across or less. */
    val canPick: Boolean get() = span <= PICK_SPAN

    /** The detail level to draw: 0 is the world outline and borders, 1 adds area detail and state lines. */
    val level: Int get() = if (span > DETAIL_SPAN) 0 else 1

    /** The whole-degree region under the crosshairs; the iNat query sends only its center. */
    val region: RegionKey get() = RegionKey.from(lat, lng)

    /** Zoomed in by [factor] (below 1 zooms out), kept between [MIN_SPAN] and [WORLD_SPAN]. */
    fun zoom(factor: Double): MapCamera = copy(span = (span / factor).coerceIn(MIN_SPAN, WORLD_SPAN))

    /** Moved by [dLng] and [dLat] degrees: longitude wraps at ±180, latitude stops at ±90. */
    fun pan(dLng: Double, dLat: Double): MapCamera = MapCamera(clamp(lat + dLat), wrap(lng + dLng), span)

    /** Centered on [region] at this zoom: the snap after a drag, a Locate, or an arrow tap. */
    fun at(region: RegionKey): MapCamera = MapCamera(clamp(region.lat.toDouble()), wrap(region.lng.toDouble()), span)

    /** One whole degree from the region under the crosshairs toward [heading]. */
    fun step(heading: Heading): MapCamera = region.let { at(RegionKey(it.lat + heading.lat, it.lng + heading.lng)) }

    /**
     * [fraction] (0 to 1) of the way to [target]: span and latitude straight, longitude the short way around, so a
     * zoom alone never moves the crosshairs and a glide never circles the globe.
     */
    fun toward(target: MapCamera, fraction: Double): MapCamera =
        copy(span = span + (target.span - span) * fraction).pan(
            eastward(lng, target.lng) * fraction,
            (target.lat - lat) * fraction,
        )

    /** Screen x of longitude [lng] in a view [width] wide, taking the shorter way around the globe. */
    fun x(lng: Double, width: Float): Float = (width / 2 + wrap(lng - this.lng) * width / span).toFloat()

    /** Screen y of latitude [lat] in a view [width] wide and [height] tall. */
    fun y(lat: Double, width: Float, height: Float): Float = (height / 2 + (this.lat - lat) * width / span).toFloat()

    /** Map constants. */
    companion object {
        /** All the way out: the whole world across. */
        const val WORLD_SPAN = 360.0

        /** All the way in. */
        const val MIN_SPAN = 2.0

        /** Zoomed in enough to pick an area. */
        const val PICK_SPAN = 12.0

        /** At or below this span the 1:50m area detail draws instead of the 1:110m world. */
        const val DETAIL_SPAN = 40.0

        /** Where Locate lands: inside [PICK_SPAN], so the found area can be picked at once. */
        const val AREA_SPAN = 8.0

        private const val MAX_LAT = 90.0
        private const val HALF_TURN = 180.0
        private const val TURN = 360.0

        /** Whole degrees with compass letters, no decimals: `38°N, 121°W`; the chip adds the "About". */
        fun degrees(region: RegionKey): String {
            val ns = if (region.lat < 0) 'S' else 'N'
            val ew = if (region.lng < 0) 'W' else 'E'
            return "${abs(region.lat)}°$ns, ${abs(region.lng)}°$ew"
        }

        /** Degrees east from [from] to [to] the short way around, in [-180, 180): a glide never circles the globe. */
        fun eastward(from: Double, to: Double): Double = wrap(to - from)

        private fun clamp(lat: Double) = lat.coerceIn(-MAX_LAT, MAX_LAT)

        private fun wrap(lng: Double) = ((lng + HALF_TURN) % TURN + TURN) % TURN - HALF_TURN
    }
}
