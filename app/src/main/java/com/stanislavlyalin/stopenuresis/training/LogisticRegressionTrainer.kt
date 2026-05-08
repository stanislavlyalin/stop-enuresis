package com.stanislavlyalin.stopenuresis.training

import kotlin.math.exp
import kotlin.math.sqrt

class LogisticRegressionTrainer(
    private val epochs: Int = 500,
    private val learningRate: Double = 0.05,
    private val l2: Double = 0.001
) {
    fun train(
        trainFeatures: List<DoubleArray>,
        trainLabels: List<Int>,
        testFeatures: List<DoubleArray>,
        testLabels: List<Int>
    ): LogisticRegressionModel {
        require(trainFeatures.isNotEmpty()) { "trainFeatures must not be empty" }
        require(trainFeatures.size == trainLabels.size) { "Train feature/label size mismatch" }
        require(testFeatures.size == testLabels.size) { "Test feature/label size mismatch" }

        val featureCount = trainFeatures.first().size
        val featureMeans = DoubleArray(featureCount)
        val featureStdDevs = DoubleArray(featureCount)

        for (features in trainFeatures) {
            for (i in 0 until featureCount) featureMeans[i] += features[i]
        }
        for (i in 0 until featureCount) featureMeans[i] /= trainFeatures.size.toDouble()

        for (features in trainFeatures) {
            for (i in 0 until featureCount) {
                val diff = features[i] - featureMeans[i]
                featureStdDevs[i] += diff * diff
            }
        }
        for (i in 0 until featureCount) {
            featureStdDevs[i] = sqrt(featureStdDevs[i] / trainFeatures.size.toDouble())
                .coerceAtLeast(1e-9)
        }

        val normalizedTrain = trainFeatures.map { normalize(it, featureMeans, featureStdDevs) }
        val weights = DoubleArray(featureCount)
        var bias = 0.0

        repeat(epochs) {
            val weightGradients = DoubleArray(featureCount)
            var biasGradient = 0.0

            for (sampleIndex in normalizedTrain.indices) {
                val prediction = sigmoid(dot(weights, normalizedTrain[sampleIndex]) + bias)
                val error = prediction - trainLabels[sampleIndex].toDouble()
                for (featureIndex in 0 until featureCount) {
                    weightGradients[featureIndex] += error * normalizedTrain[sampleIndex][featureIndex]
                }
                biasGradient += error
            }

            val sampleCount = normalizedTrain.size.toDouble()
            for (featureIndex in 0 until featureCount) {
                val gradient = weightGradients[featureIndex] / sampleCount + l2 * weights[featureIndex]
                weights[featureIndex] -= learningRate * gradient
            }
            bias -= learningRate * biasGradient / sampleCount
        }

        val accuracy = if (testFeatures.isEmpty()) {
            0.0
        } else {
            val correct = testFeatures.indices.count { index ->
                val normalized = normalize(testFeatures[index], featureMeans, featureStdDevs)
                val predicted = if (sigmoid(dot(weights, normalized) + bias) >= 0.5) 1 else 0
                predicted == testLabels[index]
            }
            correct.toDouble() / testFeatures.size.toDouble()
        }

        return LogisticRegressionModel(
            weights = weights,
            bias = bias,
            featureMeans = featureMeans,
            featureStdDevs = featureStdDevs,
            accuracy = accuracy
        )
    }

    private fun normalize(
        features: DoubleArray,
        means: DoubleArray,
        stdDevs: DoubleArray
    ): DoubleArray {
        return DoubleArray(features.size) { index ->
            (features[index] - means[index]) / stdDevs[index]
        }
    }

    private fun dot(left: DoubleArray, right: DoubleArray): Double {
        var result = 0.0
        for (i in left.indices) result += left[i] * right[i]
        return result
    }

    private fun sigmoid(value: Double): Double {
        return when {
            value >= 40.0 -> 1.0
            value <= -40.0 -> 0.0
            else -> 1.0 / (1.0 + exp(-value))
        }
    }
}
