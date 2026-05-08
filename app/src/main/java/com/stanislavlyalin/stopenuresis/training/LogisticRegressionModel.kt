package com.stanislavlyalin.stopenuresis.training

import org.json.JSONArray
import org.json.JSONObject

data class LogisticRegressionModel(
    val weights: DoubleArray,
    val bias: Double,
    val featureMeans: DoubleArray,
    val featureStdDevs: DoubleArray,
    val accuracy: Double
) {
    fun predictProbability(features: DoubleArray): Double {
        var z = bias
        for (i in weights.indices) {
            val normalized = (features[i] - featureMeans[i]) / featureStdDevs[i]
            z += weights[i] * normalized
        }
        return sigmoid(z)
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("version", 1)
            .put("featureType", "mfcc_mean_std")
            .put("classifier", "logistic_regression")
            .put("weights", weights.toJsonArray())
            .put("bias", bias)
            .put("featureMeans", featureMeans.toJsonArray())
            .put("featureStdDevs", featureStdDevs.toJsonArray())
            .put("accuracy", accuracy)
    }

    private fun DoubleArray.toJsonArray(): JSONArray {
        val array = JSONArray()
        for (value in this) array.put(value)
        return array
    }

    private fun sigmoid(value: Double): Double {
        return when {
            value >= 40.0 -> 1.0
            value <= -40.0 -> 0.0
            else -> 1.0 / (1.0 + kotlin.math.exp(-value))
        }
    }
}
