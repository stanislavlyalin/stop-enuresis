package com.stanislavlyalin.stopenuresis.training

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class MfccExtractorTest {

    @Test
    fun excludesEnergyCoefficientAndNormalizesLoudness() {
        val sampleRate = 16_000
        val quiet = FloatArray(sampleRate) { index ->
            (0.02 * sin(2.0 * Math.PI * 440.0 * index / sampleRate)).toFloat()
        }
        val loud = FloatArray(sampleRate) { index -> quiet[index] * 10f }
        val extractor = MfccExtractor()

        val quietFeatures = extractor.extractFeatures(WavAudio(sampleRate, quiet))
        val loudFeatures = extractor.extractFeatures(WavAudio(sampleRate, loud))

        assertEquals(LogisticRegressionModel.FEATURE_COUNT, quietFeatures.size)
        assertArrayEquals(quietFeatures, loudFeatures, 1e-3)
    }
}
