package com.stanislavlyalin.stopenuresis.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioEnvelopeTest {

    @Test
    fun averageSmoothedAbsoluteDoesNotUseSamplesOutsideInput() {
        val quietSamples = ShortArray(10) { 0 }

        val average = AudioEnvelope.averageSmoothedAbsolute(
            input = quietSamples,
            sampleRate = 10
        )

        assertEquals(0f, average, 0.0001f)
    }

    @Test
    fun smoothAbsoluteReturnsSameAverageAsAverageSmoothedAbsolute() {
        val samples = shortArrayOf(0, 20, -40, 60, -80, 100)
        val smoothed = FloatArray(samples.size)

        val averageFromSmoothing = AudioEnvelope.smoothAbsolute(
            input = samples,
            output = smoothed,
            sampleRate = 3
        )
        val averageDirectly = AudioEnvelope.averageSmoothedAbsolute(
            input = samples,
            sampleRate = 3
        )

        assertEquals(averageDirectly, averageFromSmoothing, 0.0001f)
        assertArrayEquals(
            floatArrayOf(0f, 10f, 20f, 40f, 60f, 80f),
            smoothed,
            0.0001f
        )
    }
}
