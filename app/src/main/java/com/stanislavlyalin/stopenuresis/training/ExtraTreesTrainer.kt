package com.stanislavlyalin.stopenuresis.training

import kotlin.math.max
import kotlin.random.Random

class ExtraTreesTrainer(private val treeCount: Int = 400) {
    fun train(trainFeatures: List<DoubleArray>, trainLabels: List<Int>, testFeatures: List<DoubleArray>, testLabels: List<Int>): ExtraTreesModel {
        require(trainFeatures.isNotEmpty() && trainFeatures.size == trainLabels.size)
        require(trainFeatures.all { it.size == AcousticFeatureExtractor.FEATURE_COUNT })
        val positiveWeight = trainFeatures.size.toDouble() / (2.0 * trainLabels.count { it == 1 }.coerceAtLeast(1))
        val negativeWeight = trainFeatures.size.toDouble() / (2.0 * trainLabels.count { it == 0 }.coerceAtLeast(1))
        val weights = DoubleArray(trainLabels.size) { index -> if (trainLabels[index] == 1) positiveWeight else negativeWeight }
        val random = Random(System.currentTimeMillis())
        val allIndexes = trainFeatures.indices.toList()
        val trees = List(treeCount) { buildNode(trainFeatures, trainLabels, weights, allIndexes, random) }
        val model = ExtraTreesModel(trees, accuracy = 0.0)
        val accuracy = if (testFeatures.isEmpty()) 0.0 else testFeatures.indices.count { index ->
            (model.predictProbability(testFeatures[index]) >= 0.5) == (testLabels[index] == 1)
        }.toDouble() / testFeatures.size
        return model.copy(accuracy = accuracy)
    }

    private fun buildNode(features: List<DoubleArray>, labels: List<Int>, weights: DoubleArray, indexes: List<Int>, random: Random): ExtraTreesModel.TreeNode {
        val probability = weightedProbability(labels, weights, indexes)
        if (indexes.size < MIN_SPLIT_SAMPLES || labelsAtSingleClass(labels, indexes)) return ExtraTreesModel.TreeNode(probability)
        val featureIndexes = (0 until AcousticFeatureExtractor.FEATURE_COUNT).shuffled(random).take(MAX_FEATURES)
        var best: Split? = null
        for (feature in featureIndexes) {
            val values = indexes.map { features[it][feature] }
            val low = values.minOrNull()!!
            val high = values.maxOrNull()!!
            if (high <= low) continue
            val threshold = low + random.nextDouble() * (high - low)
            val left = indexes.filter { features[it][feature] <= threshold }
            val right = indexes.filter { features[it][feature] > threshold }
            if (left.size < MIN_LEAF_SAMPLES || right.size < MIN_LEAF_SAMPLES) continue
            val impurity = weightedGini(labels, weights, left) + weightedGini(labels, weights, right)
            if (best == null || impurity < best.impurity) best = Split(feature, threshold, left, right, impurity)
        }
        val split = best ?: return ExtraTreesModel.TreeNode(probability)
        return ExtraTreesModel.TreeNode(probability, split.feature, split.threshold,
            buildNode(features, labels, weights, split.left, random), buildNode(features, labels, weights, split.right, random))
    }

    private fun labelsAtSingleClass(labels: List<Int>, indexes: List<Int>): Boolean = indexes.all { labels[it] == labels[indexes.first()] }

    private fun weightedProbability(labels: List<Int>, weights: DoubleArray, indexes: List<Int>): Double {
        val total = indexes.sumOf { weights[it] }
        return indexes.filter { labels[it] == 1 }.sumOf { weights[it] } / total.coerceAtLeast(1e-12)
    }

    private fun weightedGini(labels: List<Int>, weights: DoubleArray, indexes: List<Int>): Double {
        val total = indexes.sumOf { weights[it] }
        val positive = indexes.filter { labels[it] == 1 }.sumOf { weights[it] }
        val probability = positive / total.coerceAtLeast(1e-12)
        return total * (1.0 - probability * probability - (1.0 - probability) * (1.0 - probability))
    }

    private data class Split(val feature: Int, val threshold: Double, val left: List<Int>, val right: List<Int>, val impurity: Double)

    private companion object {
        const val MIN_LEAF_SAMPLES = 2
        const val MIN_SPLIT_SAMPLES = MIN_LEAF_SAMPLES * 2
        const val MAX_FEATURES = 22
    }
}
