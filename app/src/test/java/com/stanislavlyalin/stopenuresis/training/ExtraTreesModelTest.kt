package com.stanislavlyalin.stopenuresis.training

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraTreesModelTest {
    @Test
    fun predictsFromSelectedBranch() {
        val model = ExtraTreesModel(listOf(
            ExtraTreesModel.TreeNode(0.5, 0, 1.0,
                ExtraTreesModel.TreeNode(0.1), ExtraTreesModel.TreeNode(0.9)
            )
        ), 1.0)

        assertEquals(0.1, model.predictProbability(DoubleArray(AcousticFeatureExtractor.FEATURE_COUNT)), 0.0)
        assertEquals(0.9, model.predictProbability(DoubleArray(AcousticFeatureExtractor.FEATURE_COUNT) { 2.0 }), 0.0)
    }
}
