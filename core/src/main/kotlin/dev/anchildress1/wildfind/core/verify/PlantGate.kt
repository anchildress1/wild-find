package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.dotAt
import kotlin.math.exp

/**
 * TinyCLIP plant gate: softmax over [logitScale] times cosine against the gate labels.
 *
 * @property labels the plant and not-plant label rows, each a unit vector
 * @property logitScale TinyCLIP's learned scale, exp(logit_scale); raw cosines softmaxed without it give other verdicts
 */
class PlantGate(val labels: List<Label>, val logitScale: Float) {
    init {
        require(labels.any { it.isPlant } && labels.any { !it.isPlant }) { "need plant and not-plant labels" }
        require(labels.map { it.vector.size }.distinct().size == 1) { "label vectors differ in size" }
    }

    /**
     * One gate label.
     *
     * @property isPlant true when the label describes a plant
     * @property vector unit text embedding
     */
    class Label(val isPlant: Boolean, val vector: FloatArray)

    /** Combined softmax probability of the plant labels for a unit image [embedding]. */
    fun plantShare(embedding: FloatArray): Double {
        require(embedding.size == labels.first().vector.size) { "embedding size ${embedding.size}" }
        val logits = labels.map { label ->
            logitScale * label.vector.dotAt(0, embedding)
        }
        val max = logits.max()
        val weights = logits.map { exp(it - max) }
        return labels.indices.filter { labels[it].isPlant }.sumOf { weights[it] } / weights.sum()
    }

    /** Gate constants measured on Day 1. */
    companion object {
        /** A frame is a plant above this combined plant share. */
        const val THRESHOLD = 0.5

        /** True when a [plantShare] result is over [THRESHOLD]. */
        fun isPlant(share: Double): Boolean = share > THRESHOLD
    }
}
