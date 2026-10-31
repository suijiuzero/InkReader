package com.inkreader.parse

import java.util.PriorityQueue
import kotlin.math.exp

/** Trained logistic model. Advisory only: caller must never auto-accept its suggestions. */
internal object HeadingModel {
    data class Suggestion(val index: Int, val score: Double)
    private val coefficients = HeadingModelWeights.coefficients.copyOf()
    val available = HeadingModelWeights.features == HeadingModelWeights.features &&
        coefficients.size == HeadingModelWeights.features.size + 1 && coefficients.all { it.isFinite() } &&
        HeadingModelWeights.threshold.isFinite() && HeadingModelWeights.threshold in 0.0..1.0

    /** 32 维（与 `HeadingFeatures.at32` 对应）；21 维旧权重时退化为 [HeadingFeatures.at]。 */
    private val usesV2 = HeadingModelWeights.features == HeadingFeatures.namesV2

    fun score(features: DoubleArray, weights: DoubleArray = coefficients): Double? {
        if (features.size + 1 != weights.size || features.any { !it.isFinite() } || weights.any { !it.isFinite() }) return null
        var value = weights[0]
        for (i in features.indices) value += features[i] * weights[i + 1]
        if (!value.isFinite()) return null
        return 1.0 / (1.0 + exp(-value.coerceIn(-40.0, 40.0)))
    }

    /**
     * 低门槛打分：返回分数 >= [floor] 的候选（行号 → 分数）。
     * 结构校验的 R2（编号链补漏）需要看到阈值以下的候选，否则永远补不回来。
     */
    fun scoredCandidates(lines: List<String>, fenced: BooleanArray, excluded: Set<Int>,
                         floor: Double = 0.25): Map<Int, Double> {
        if (!available) return emptyMap()
        val features = HeadingFeatures(lines)
        val out = LinkedHashMap<Int, Double>()
        for ((position, i) in features.candidates.withIndex()) {
            if (position and 255 == 0) ParseCancellation.checkpoint()
            if (fenced[i] || i in excluded) continue
            val vector = if (usesV2) features.at32(i) else features.at(i)
            val score = score(vector) ?: continue
            if (score >= floor) out[i] = score
        }
        return out
    }

    fun suggest(lines: List<String>, fenced: BooleanArray, excluded: Set<Int>): List<Suggestion> {
        val best = PriorityQueue<Suggestion>(compareBy<Suggestion> { it.score }.thenByDescending { it.index })
        for ((i, score) in scoredCandidates(lines, fenced, excluded, HeadingModelWeights.threshold)) {
            best += Suggestion(i, score)
            if (best.size > 200) best.poll()
        }
        return best.sortedBy { it.index }
    }
}
