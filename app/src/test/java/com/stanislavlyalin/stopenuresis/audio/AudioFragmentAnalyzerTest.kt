package com.stanislavlyalin.stopenuresis.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFragmentAnalyzerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun analyzeBuildsWaveformFromSignedPcmSamples() {
        val file = temporaryFolder.newFile("signed.wav")
        ManualWavFileWriter().writeWav(
            outputFile = file,
            pcm16MonoSamples = shortArrayOf(-100, 50, -25, 100),
            sampleRate = 4
        )

        val info = AudioFragmentAnalyzer.analyze(file)

        assertEquals(4, info.waveform.size)
        assertEquals(-100f, info.waveform[0].minAmplitude, 0.0001f)
        assertEquals(0f, info.waveform[0].maxAmplitude, 0.0001f)
        assertEquals(0f, info.waveform[1].minAmplitude, 0.0001f)
        assertEquals(50f, info.waveform[1].maxAmplitude, 0.0001f)
        assertEquals(-25f, info.waveform[2].minAmplitude, 0.0001f)
        assertEquals(0f, info.waveform[2].maxAmplitude, 0.0001f)
        assertEquals(0f, info.waveform[3].minAmplitude, 0.0001f)
        assertEquals(100f, info.waveform[3].maxAmplitude, 0.0001f)
    }
}
