package com.stanislavlyalin.stopenuresis.training

import org.json.JSONArray
import org.json.JSONObject

data class ExtraTreesModel(val trees: List<TreeNode>, val accuracy: Double) {
    init { require(trees.isNotEmpty()) { "At least one tree is required" } }

    fun predictProbability(features: DoubleArray): Double {
        require(features.size == AcousticFeatureExtractor.FEATURE_COUNT) { "Unexpected feature count" }
        return trees.sumOf { it.predict(features) } / trees.size
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", MODEL_VERSION)
        .put("featureType", FEATURE_TYPE)
        .put("classifier", "extra_trees")
        .put("trees", JSONArray().also { array -> trees.forEach { array.put(it.toJson()) } })
        .put("accuracy", accuracy)

    data class TreeNode(
        val probability: Double,
        val featureIndex: Int? = null,
        val threshold: Double = 0.0,
        val left: TreeNode? = null,
        val right: TreeNode? = null
    ) {
        fun predict(features: DoubleArray): Double = if (featureIndex == null) {
            probability
        } else if (features[featureIndex] <= threshold) {
            left!!.predict(features)
        } else {
            right!!.predict(features)
        }

        fun toJson(): JSONObject = JSONObject().put("p", probability).also { json ->
            featureIndex?.let { index ->
                json.put("f", index).put("t", threshold).put("l", left!!.toJson()).put("r", right!!.toJson())
            }
        }

        companion object {
            fun fromJson(json: JSONObject): TreeNode {
                if (!json.has("f")) return TreeNode(probability = json.getDouble("p"))
                return TreeNode(
                    probability = json.getDouble("p"), featureIndex = json.getInt("f"), threshold = json.getDouble("t"),
                    left = fromJson(json.getJSONObject("l")), right = fromJson(json.getJSONObject("r"))
                )
            }
        }
    }

    companion object {
        private const val MODEL_VERSION = 3
        private const val FEATURE_TYPE = "selected_acoustic_features_v1"

        fun fromJson(json: JSONObject): ExtraTreesModel {
            require(json.optInt("version") == MODEL_VERSION) { "Unsupported model version" }
            require(json.optString("featureType") == FEATURE_TYPE && json.optString("classifier") == "extra_trees") { "Unsupported model features" }
            val trees = json.getJSONArray("trees")
            return ExtraTreesModel(List(trees.length()) { TreeNode.fromJson(trees.getJSONObject(it)) }, json.optDouble("accuracy", 0.0))
        }
    }
}
