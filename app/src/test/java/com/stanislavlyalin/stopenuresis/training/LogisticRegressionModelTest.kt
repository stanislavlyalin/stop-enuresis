package com.stanislavlyalin.stopenuresis.training

import org.junit.Assert.assertEquals
import org.junit.Test

class LogisticRegressionModelTest {

    @Test
    fun rejectsLegacyFeatureCount() {
        try {
            LogisticRegressionModel(
                weights = DoubleArray(26),
                bias = 0.0,
                featureMeans = DoubleArray(26),
                featureStdDevs = DoubleArray(26) { 1.0 },
                accuracy = 1.0
            )
            throw AssertionError("Legacy model must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun predictsWithCompatibleFeatureSet() {
        val model = LogisticRegressionModel(
            weights = DoubleArray(LogisticRegressionModel.FEATURE_COUNT),
            bias = 0.0,
            featureMeans = DoubleArray(LogisticRegressionModel.FEATURE_COUNT),
            featureStdDevs = DoubleArray(LogisticRegressionModel.FEATURE_COUNT) { 1.0 },
            accuracy = 1.0
        )

        assertEquals(0.5, model.predictProbability(DoubleArray(LogisticRegressionModel.FEATURE_COUNT)), 0.0)
    }
}
