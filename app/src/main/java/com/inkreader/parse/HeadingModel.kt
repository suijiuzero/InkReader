package com.inkreader.parse

import java.util.PriorityQueue
import kotlin.math.exp

/** Trained logistic model. Advisory only: caller must never auto-accept its suggestions. */
internal object HeadingModel {
    data class Suggestion(val index: Int, val score: Double)
    private val coefficients = HeadingModelWeights.coefficients.copyOf()
    val available = HeadingFeatures.names == HeadingModelWeights.features &&
        coefficients.size == HeadingFeatures.names.size + 1 && coefficients.all { it.isFinite() } &&
        HeadingModelWeights.threshold.isFinite() && HeadingModelWeights.threshold in 0.0..1.0

    fun score(features: DoubleArray, weights: DoubleArray = coefficients): Double? {
        if (features.size + 1 != weights.size || features.any { !it.isFinite() } || weights.any { !it.isFinite() }) return null
        var value = weights[0]
        for (i in features.indices) value += features[i] * weights[i + 1]
        if (!value.isFinite()) return null
        return 1.0 / (1.0 + exp(-value.coerceIn(-40.0, 40.0)))
    }

    fun suggest(lines: List<String>, fenced: BooleanArray, excluded: Set<Int>): List<Suggestion> {
        if (!available) return emptyList()
        val features = HeadingFeatures(lines)
        val best = PriorityQueue<Suggestion>(compareBy<Suggestion> { it.score }.thenByDescending { it.index })
        for ((position, i) in features.candidates.withIndex()) {
            if (position and 255 == 0) ParseCancellation.checkpoint()
            if (fenced[i] || i in excluded) continue
            val score = score(features.at(i)) ?: continue
            if (score < HeadingModelWeights.threshold) continue
            best += Suggestion(i, score)
            if (best.size > 200) best.poll()
        }
        return best.sortedBy { it.index }
    }
}
