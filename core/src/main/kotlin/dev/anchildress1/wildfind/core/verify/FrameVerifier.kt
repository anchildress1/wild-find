package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.frame.Bicubic
import dev.anchildress1.wildfind.core.frame.Crops
import dev.anchildress1.wildfind.core.frame.Pixels
import dev.anchildress1.wildfind.core.frame.RgbaFrame

/** An image encoder: a [Crops.MODEL_SIZE]-square RGB image in, a unit embedding out. */
fun interface ImageEmbedder {
    /** Embeds [pixels]. */
    fun embed(pixels: Pixels): FloatArray
}

/**
 * Nanoseconds spent in each stage of one frame.
 *
 * @property crop copying both upright crops out of the camera buffer
 * @property resize both bicubic resizes to model size
 * @property plantGate both TinyCLIP embeddings and gate scores
 * @property bioclip the BioCLIP embeddings, zero to two
 * @property hazard species-table ranking of each BioCLIP embedding
 * @property goal label scoring of the reticle embedding
 * @property total the whole frame, including the focus lookup
 */
data class StageTimes(
    val crop: Long,
    val resize: Long,
    val plantGate: Long,
    val bioclip: Long,
    val hazard: Long,
    val goal: Long,
    val total: Long,
)

/**
 * Everything one frame produced; [evidence] feeds [VerifyStreak], the rest is for the found screen and the debug log.
 *
 * @property evidence the verify-table inputs
 * @property reticle the upright reticle crop at analysis resolution; on Found, this is the capture
 * @property reticleShare TinyCLIP plant share of the reticle crop
 * @property fullShare TinyCLIP plant share of the full-frame crop
 * @property reticleHazardRank best hazard rank on the reticle crop, or null when it wasn't checked
 * @property fullHazardRank best hazard rank on the full frame, or null when it wasn't checked
 * @property goal the goal score, or null when the reticle crop isn't a plant
 * @property times per-stage durations
 */
data class FrameResult(
    val evidence: FrameEvidence,
    val reticle: Pixels,
    val reticleShare: Double,
    val fullShare: Double,
    val reticleHazardRank: Int?,
    val fullHazardRank: Int?,
    val goal: GoalScore?,
    val times: StageTimes,
)

/**
 * The per-frame verify path from the PRD Runtime Logic: crop, plant-gate both regions, BioCLIP each plant region,
 * check hazards, score the goal.
 *
 * @param gate plant-gate labels and scale
 * @param gateEncoder TinyCLIP image encoder
 * @param bioclip BioCLIP 2.5 Mobile image encoder
 * @param hazards species-table hazard rule
 * @param clock monotonic nanoseconds
 */
class FrameVerifier(
    private val gate: PlantGate,
    private val gateEncoder: ImageEmbedder,
    private val bioclip: ImageEmbedder,
    private val hazards: HazardCheck,
    private val clock: () -> Long = System::nanoTime,
) {
    /**
     * Analyzes [frame] for [goal].
     *
     * @param focus returns this frame's own autofocus reading; asked last, so the capture result has time to arrive
     */
    fun analyze(frame: RgbaFrame, goal: Goal, focus: () -> Focus?): FrameResult {
        val start = clock()
        val reticle = frame.upright(Crops.reticle(frame.width, frame.height))
        val full = frame.upright(Crops.fullFrame(frame.width, frame.height))
        val cropped = clock()
        val reticleInput = Bicubic.resize(reticle, Crops.MODEL_SIZE, Crops.MODEL_SIZE)
        val fullInput = Bicubic.resize(full, Crops.MODEL_SIZE, Crops.MODEL_SIZE)
        val resized = clock()
        val reticleShare = gate.plantShare(embed(gateEncoder, reticleInput, "TinyCLIP reticle"))
        val fullShare = gate.plantShare(embed(gateEncoder, fullInput, "TinyCLIP full frame"))
        val reticlePlant = PlantGate.isPlant(reticleShare)
        val gated = clock()
        // BioCLIP runs only on regions the gate calls a plant; the full frame only feeds the hazard check.
        val reticleEmbedding = if (reticlePlant) embed(bioclip, reticleInput, "BioCLIP reticle") else null
        val fullPlant = PlantGate.isPlant(fullShare)
        val fullEmbedding = if (goal.checksHazards &&
            fullPlant
        ) {
            embed(bioclip, fullInput, "BioCLIP full frame")
        } else {
            null
        }
        val embedded = clock()
        val reticleRank = reticleEmbedding?.takeIf { goal.checksHazards }?.let(hazards::bestHazardRank)
        val fullRank = fullEmbedding?.let(hazards::bestHazardRank)
        val hazard = listOfNotNull(reticleRank, fullRank).any(HazardCheck::warns)
        val ranked = clock()
        val score = reticleEmbedding?.let(goal::score)
        val scored = clock()
        val evidence = FrameEvidence(hazard, reticlePlant, focus(), score?.met == true)
        val end = clock()
        return FrameResult(
            evidence,
            reticle,
            reticleShare,
            fullShare,
            reticleRank,
            fullRank,
            score,
            StageTimes(
                crop = cropped - start,
                resize = resized - cropped,
                plantGate = gated - resized,
                bioclip = embedded - gated,
                hazard = ranked - embedded,
                goal = scored - ranked,
                total = end - start,
            ),
        )
    }

    // fp16 BioCLIP returned NaN on the phone's ARM CPU (S03); a NaN embedding would rank every hazard first.
    private fun embed(encoder: ImageEmbedder, pixels: Pixels, what: String): FloatArray =
        encoder.embed(pixels).also { v -> check(v.all(Float::isFinite)) { "$what embedding is not finite" } }
}
