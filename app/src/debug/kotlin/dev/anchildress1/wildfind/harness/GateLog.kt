package dev.anchildress1.wildfind.harness

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File

/**
 * One gate-harness run's files, flushed on every row so a killed process loses nothing.
 *
 * They live in the app's external files dir, which `adb pull` reads without root (`make gate-pull`).
 *
 * @param dir this run's directory
 */
class GateLog(val dir: File) : Closeable {
    private val frames = open("frames.csv", FRAME_COLUMNS)
    private val system = open("system.csv", SYSTEM_COLUMNS)
    private val hints = open("hints.csv", HINT_COLUMNS)
    private val events = open("events.csv", EVENT_COLUMNS)

    /** Writes `run.json`: what this run measured and on what. */
    fun run(info: JSONObject) = File(dir, "run.json").writeText(info.toString(1) + "\n")

    /** One analyzed frame, in [FRAME_COLUMNS] order. */
    fun frame(vararg fields: Any?) = frames.row(FRAME_COLUMNS, fields)

    /** One system sample, in [SYSTEM_COLUMNS] order. */
    fun system(vararg fields: Any?) = system.row(SYSTEM_COLUMNS, fields)

    /** One hint tap, in [HINT_COLUMNS] order. */
    fun hint(vararg fields: Any?) = hints.row(HINT_COLUMNS, fields)

    /** One run event, in [EVENT_COLUMNS] order. */
    fun event(vararg fields: Any?) = events.row(EVENT_COLUMNS, fields)

    // Synchronized with row writes, and every writer gets its close even if one throws.
    @Synchronized
    override fun close() {
        val failures = listOf(frames, system, hints, events).mapNotNull { runCatching(it::close).exceptionOrNull() }
        failures.firstOrNull()?.let { first -> throw first.also { failures.drop(1).forEach(it::addSuppressed) } }
    }

    private fun open(name: String, columns: List<String>) = File(dir, name).bufferedWriter().also {
        // Flushed at once, so a run that dies before its first row still leaves a readable, empty CSV.
        it.write(columns.joinToString(",") + "\n")
        it.flush()
    }

    @Synchronized
    private fun BufferedWriter.row(columns: List<String>, fields: Array<out Any?>) {
        require(fields.size == columns.size) { "${fields.size} fields for ${columns.size} columns" }
        write(fields.joinToString(",") { csv(it) } + "\n")
        flush()
    }

    // Hint text is model output: quote every field that could break a row.
    private fun csv(value: Any?): String {
        val text = value?.toString() ?: ""
        return if (text.any { it in CSV_SPECIAL }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
    }

    /** Column names; `pipeline/src/wild_find_pipeline/gate_summary.py` reads them by name. */
    companion object {
        /** Times are `elapsedRealtimeNanos`; stage durations are milliseconds. */
        val FRAME_COLUMNS = listOf(
            "t_ns", "sensor_ns", "gap_ms", "frame_w", "frame_h", "rotation", "verdict", "streak",
            "reticle_share", "full_share", "reticle_hazard_rank", "full_hazard_rank",
            "reticle_top", "reticle_hazard", "full_top", "full_hazard", "goal_score", "goal_rank",
            "af_state", "diopters", "zoom", "focus_matched",
            "crop_ms", "resize_ms", "gate_ms", "bioclip_ms", "hazard_ms", "goal_ms", "verify_ms", "frame_ms",
        )

        /** Sampled every [GateRun.SAMPLE_MS], never on the analysis thread. */
        val SYSTEM_COLUMNS = listOf(
            "t_ns", "pss_kb", "avail_mem_kb", "low_memory", "thermal_status", "thermal_headroom",
            "battery_temp_c", "battery_pct", "charging",
        )

        /**
         * One row per hint tap: frame copy, JPEG, scene call, hint call, and tap to hint shown (`total_ms`). Every tap
         * runs its own scene call, so `lead_ms` and `wait_ms` are always 0.
         */
        val HINT_COLUMNS = listOf(
            "tap_ns", "lead_ms", "frame_ms", "jpeg_ms", "scene_ms", "wait_ms", "hint_ms", "total_ms",
            "jpeg_bytes", "tags", "scene_reply", "hint",
        )

        /** Lifecycle, model loads, and errors. */
        val EVENT_COLUMNS = listOf("t_ns", "event", "detail")

        private const val CSV_SPECIAL = ",\"\n\r"
    }
}
