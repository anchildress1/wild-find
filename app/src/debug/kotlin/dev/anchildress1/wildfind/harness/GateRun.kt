package dev.anchildress1.wildfind.harness

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import dev.anchildress1.wildfind.Models
import dev.anchildress1.wildfind.camera.CapturedFrame
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.Goal
import dev.anchildress1.wildfind.core.verify.TargetGoal
import dev.anchildress1.wildfind.core.verify.Verdict
import dev.anchildress1.wildfind.core.verify.VerifyStreak
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the harness screen shows.
 *
 * @property verdict the last frame's verdict
 * @property frameMs the last frame's time from buffer to verdict
 * @property found Found verdicts this run
 * @property pssMb this process's last sampled PSS
 * @property thermalStatus last sampled `PowerManager` thermal status
 * @property species the reticle's top-1 species-table row on the last frame BioCLIP ran, or empty
 * @property capturing true while a capture's frames are being verified
 * @property target the scientific name this run looks for, or [GateRun.TUTORIAL]
 */
data class GateStatus(
    val verdict: Verdict? = null,
    val frameMs: Double = 0.0,
    val found: Int = 0,
    val pssMb: Long = 0,
    val thermalStatus: Int = 0,
    val species: String = "",
    val capturing: Boolean = false,
    val target: String = "",
)

/**
 * One gate-harness run: the real verify path on each capture tap, and memory, heat, and battery sampled off the
 * analysis thread, all logged by [log]. Between taps the camera only previews.
 *
 * Construct off the main thread: it reads the local species list.
 *
 * @param models the app's loaded models, shared so harness memory readings count each ONNX session once
 * @param target the target's scientific name, or [TUTORIAL] for the grass tutorial goal
 */
class GateRun(private val context: Context, models: Models, private val target: String, val log: GateLog) :
    Closeable {
    private val species = models.rows.map { it.scientific }
    private val names = models.names

    // West Georgia's October pull, a debug asset; the hunt's own iNat pull replaces it in the app.
    private val sightings = context.assets.open(LOCAL_SPECIES).bufferedReader().useLines { lines ->
        lines.filterNot { it.isBlank() || it.startsWith("#") }.map { line ->
            line.split('\t').let { Sighting(it[1], it.getOrNull(2)?.ifBlank { null }, it[0].toInt()) }
        }.toList()
    }
    private val local = LocalSpecies(models.rows, names::rowOf).of(sightings)

    // The hunt's eligible species compete, with the local toxic and hazard species as blockers.
    private val goal: Goal = if (target == TUTORIAL) {
        models.tutorial
    } else {
        TargetGoal(
            models.table,
            models.genus,
            requireNotNull(local.eligible.firstOrNull { species[it.row] == target }) { "$target is not eligible" }.row,
            local.eligible.map { it.row }.toIntArray(),
            local.blockers,
        )
    }
    private val sampler = Executors.newSingleThreadScheduledExecutor()
    private val state = MutableStateFlow(GateStatus(target = target))

    private var lastFrameNs = 0L

    /**
     * The capture loop the camera feeds, naming from the same local rows as the app's hunt so harness logs measure
     * the shipped path; focus_matched=false in the log means a frame had no reading.
     */
    val verifier = models.verifier((local.eligible.map { it.row } + local.blockers.toList()).toSet(), WEST_GEORGIA)

    /** Screen state. */
    val status: StateFlow<GateStatus> = state.asStateFlow()

    /** Starts sampling. */
    fun start() {
        log.event(now(), "start", target)
        log.event(
            now(),
            "local_species",
            "${sightings.count {
                names.rowOf(it.scientific) != null
            }} of ${sightings.size} local names in the species table; " +
                "${local.eligible.size} eligible, ${local.blockers.size} blockers",
        )
        sampler.scheduleWithFixedDelay(::sample, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * One capture tap: verify the next [VerifyStreak.FRAMES] frames back to back, stopping at the first that breaks
     * the streak, so a Found still needs that many matching frames in a row.
     */
    fun requestCapture() {
        // Main thread only. The button state goes first: set after the capture starts, it could land after the
        // analyzer already finished it and disable the button for the rest of the run.
        if (verifier.capturing) return
        state.update { it.copy(capturing = true) }
        log.event(now(), "capture", target)
        verifier.capture(goal, ::record)
    }

    private fun record(frame: CapturedFrame) {
        val result = frame.result
        val verdict = frame.verdict
        // A capture's first frame has no gap: the time since the last capture is the tester's pause, not cadence.
        val gapMs = if (frame.first) null else ms(frame.receivedNs - lastFrameNs)
        lastFrameNs = frame.receivedNs
        val frameMs = ms(frame.doneNs - frame.receivedNs)
        val reading = result.evidence.focus
        val times = result.times
        val streakFrames = when (verdict) {
            is Verdict.Matching -> verdict.frames
            Verdict.Found -> VerifyStreak.FRAMES
            else -> 0
        }
        val reticleRank = result.reticleRanking
        val fullRank = result.fullRanking
        val upright = verifier.frameSize.value
        log.frame(
            frame.receivedNs, frame.sensorNs, gapMs, upright?.width, upright?.height, frame.rotation, name(verdict),
            streakFrames, result.reticleShare, result.fullShare, reticleRank?.hazardRank, fullRank?.hazardRank,
            reticleRank?.let { species[it.topRow] }, reticleRank?.hazardRow?.let { species[it] },
            fullRank?.let { species[it.topRow] }, fullRank?.hazardRow?.let { species[it] },
            result.goal?.score, result.goal?.rank,
            reading?.afState, reading?.diopters, reading?.zoomRatio, reading != null,
            ms(times.crop), ms(times.resize), ms(times.plantGate), ms(times.bioclip), ms(times.hazard), ms(times.goal),
            ms(times.total), frameMs,
        )
        state.update {
            it.copy(
                verdict = verdict,
                frameMs = frameMs,
                found = it.found + if (verdict == Verdict.Found) 1 else 0,
                species = reticleRank?.let { rank -> species[rank.topRow] } ?: it.species,
                capturing = verifier.capturing,
            )
        }
    }

    /** Stops sampling and closes the log; the app owns the models. Call after the camera stops delivering frames. */
    override fun close() {
        sampler.shutdown()
        // A sample stuck under heat can outlast the wait; the log says so instead of hiding it.
        val drained = sampler.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS)
        log.event(now(), if (drained) "stop" else "stop_timeout", target)
        // A thread still running would write to a closed log and crash; every row is already flushed, so leaving the
        // files open loses nothing.
        if (drained) log.close()
    }

    // scheduleWithFixedDelay cancels the schedule on the first uncaught exception without a trace, which would
    // end the thermal record mid-run while the screen kept its last values; log it and keep sampling.
    @Suppress("TooGenericExceptionCaught")
    private fun sample() {
        try {
            sampleOnce()
        } catch (e: Exception) {
            log.event(now(), "sample_error", e.toString())
        }
    }

    private fun sampleOnce() {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val pss = Debug.getPss()
        val thermal = power.currentThermalStatus
        // Missing battery extras log empty, never a made-up 0 °C or 100 %.
        log.system(
            now(), pss, memory.availMem / BYTES_PER_KB, memory.lowMemory, thermal,
            power.getThermalHeadroom(HEADROOM_FORECAST_S),
            if (tenths == Int.MIN_VALUE) null else tenths / TENTHS,
            if (level < 0 || scale <= 0) null else level * PERCENT / scale,
            battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }
                ?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 },
        )
        state.update { it.copy(pssMb = pss / BYTES_PER_KB, thermalStatus = thermal) }
    }

    /** Constants for one run. */
    companion object {
        /** The [GateRun] target that selects the grass tutorial goal. */
        const val TUTORIAL = "grass"

        /** System sampling period; `getThermalHeadroom` returns NaN when called more than once a second. */
        const val SAMPLE_MS = 2_000L

        private const val LOCAL_SPECIES = "local_species.tsv"

        // LOCAL_SPECIES is West Georgia's pull, so harness runs check hazards as a hunt there does.
        private val WEST_GEORGIA = RegionKey(34, -85)
        private const val NANOS_PER_MS = 1_000_000L
        private const val BYTES_PER_KB = 1024
        private const val TENTHS = 10.0
        private const val PERCENT = 100
        private const val HEADROOM_FORECAST_S = 0
        private const val CLOSE_WAIT_S = 30L

        private fun now() = SystemClock.elapsedRealtimeNanos()

        private fun ms(nanos: Long) = nanos.toDouble() / NANOS_PER_MS

        private fun name(verdict: Verdict) = when (verdict) {
            is Verdict.Hazard -> "hazard"
            Verdict.NotPlant -> "not_plant"
            Verdict.TapToFocus -> "tap_to_focus"
            Verdict.WalkCloser -> "walk_closer"
            is Verdict.Matching -> "matching"
            Verdict.Found -> "found"
            Verdict.Guide -> "guide"
        }
    }
}
