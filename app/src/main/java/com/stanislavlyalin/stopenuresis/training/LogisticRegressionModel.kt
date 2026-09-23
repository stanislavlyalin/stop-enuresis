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
    init {
        require(weights.size == FEATURE_COUNT) { "Unexpected weight count" }
        require(featureMeans.size == FEATURE_COUNT) { "Unexpected feature mean count" }
        require(featureStdDevs.size == FEATURE_COUNT) { "Unexpected feature standard deviation count" }
    }

    fun predictProbability(features: DoubleArray): Double {
        require(features.size == FEATURE_COUNT) { "Unexpected feature count" }
        var z = bias
        for (i in weights.indices) {
            val normalized = (features[i] - featureMeans[i]) / featureStdDevs[i]
            z += weights[i] * normalized
        }
        return sigmoid(z)
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("version", MODEL_VERSION)
            .put("featureType", FEATURE_TYPE)
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

    companion object {
        const val FEATURE_COUNT = 24
        private const val MODEL_VERSION = 2
        private const val FEATURE_TYPE = "mfcc_mean_std_without_c0"

        fun fromJson(jsonObject: JSONObject): LogisticRegressionModel {
            require(jsonObject.optInt("version") == MODEL_VERSION) { "Unsupported model version" }
            require(jsonObject.optString("featureType") == FEATURE_TYPE) {
                "Unsupported model features"
            }
            return LogisticRegressionModel(
                weights = jsonObject.getJSONArray("weights").toDoubleArray(),
                bias = jsonObject.getDouble("bias"),
                featureMeans = jsonObject.getJSONArray("featureMeans").toDoubleArray(),
                featureStdDevs = jsonObject.getJSONArray("featureStdDevs").toDoubleArray(),
                accuracy = jsonObject.optDouble("accuracy", 0.0)
            )
        }

        private fun JSONArray.toDoubleArray(): DoubleArray {
            return DoubleArray(length()) { index -> getDouble(index) }
        }
    }
}
