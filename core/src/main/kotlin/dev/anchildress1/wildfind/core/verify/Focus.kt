package dev.anchildress1.wildfind.core.verify

/**
 * One autofocus reading from a Camera2 capture result.
 *
 * @property afState `CONTROL_AF_STATE`, or null when the result lacks it
 * @property diopters `LENS_FOCUS_DISTANCE`: 0 is infinity, larger is nearer; null when missing
 * @property zoomRatio `CONTROL_ZOOM_RATIO`
 */
data class Focus(val afState: Int?, val diopters: Float?, val zoomRatio: Float) {
    /** PRD verify row 3: autofocus reports focused or locked with a non-negative distance. */
    val isFocused: Boolean
        get() = afState in FOCUSED_STATES && diopters != null && diopters >= 0f

    /** PRD verify row 5, set on the test phone on Day 1: a focused reading with diopters times zoom at or above 2.0. */
    val isClose: Boolean
        get() = isFocused && diopters != null && diopters.toDouble() * zoomRatio >= CLOSE_RANGE

    /** Camera2 constants the rules read; core stays free of `android.*`. */
    companion object {
        /** `CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED`. */
        const val AF_PASSIVE_FOCUSED = 2

        /** `CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED`. */
        const val AF_FOCUSED_LOCKED = 4

        /** Smallest diopters × zoom ratio that counts as close range. */
        const val CLOSE_RANGE = 2.0

        /**
         * The zoom ratio a capture used: `CONTROL_ZOOM_RATIO` when the result has it, else the sensor's active-array
         * width over `SCALER_CROP_REGION`'s, which is how CameraX zooms cameras without ratio control.
         *
         * Null when the result has neither, or the array width isn't known yet, so no reading is ever made up.
         */
        fun zoomRatio(controlZoom: Float?, activeArrayWidth: Int, cropRegionWidth: Int?): Float? = when {
            controlZoom != null -> controlZoom

            activeArrayWidth > 0 && cropRegionWidth != null && cropRegionWidth > 0 ->
                activeArrayWidth.toFloat() / cropRegionWidth

            else -> null
        }

        private val FOCUSED_STATES = setOf(AF_PASSIVE_FOCUSED, AF_FOCUSED_LOCKED)
    }
}

/**
 * Recent focus readings keyed by sensor timestamp, so each analysis frame gets the reading of its own capture.
 *
 * Capture results and analysis frames arrive on different threads; the latest reading can belong to another frame,
 * and unfocused frames park the lens near 0.2 diopters, so a mismatched reading flips "walk closer".
 *
 * @param capacity readings kept; about a second of 30 fps capture results by default
 */
class FocusTrack(private val capacity: Int = DEFAULT_CAPACITY) {
    private val timestamps = LongArray(capacity)
    private val readings = arrayOfNulls<Focus>(capacity)
    private var next = 0

    init {
        require(capacity > 0) { "capacity $capacity" }
    }

    /** Records the reading of the capture taken at [timestampNs] (`SENSOR_TIMESTAMP`). */
    @Synchronized
    fun record(timestampNs: Long, focus: Focus) {
        timestamps[next] = timestampNs
        readings[next] = focus
        next = (next + 1) % capacity
    }

    /** The reading recorded for [timestampNs], or null when that capture's result never arrived or has aged out. */
    @Synchronized
    fun at(timestampNs: Long): Focus? = (0 until capacity).firstOrNull {
        readings[it] != null && timestamps[it] == timestampNs
    }?.let { readings[it] }

    private companion object {
        const val DEFAULT_CAPACITY = 32
    }
}
