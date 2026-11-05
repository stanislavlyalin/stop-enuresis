package com.stanislavlyalin.stopenuresis.training

import com.stanislavlyalin.stopenuresis.audio.ManualWavFileWriter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrainingRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun getDatasetStatsIncludesCentralLevelRanges() {
        val samplesDir = temporaryFolder.newFolder("samples")
        val repository = TrainingRepository(
            samplesDir = samplesDir,
            modelFile = temporaryFolder.newFile("model.json")
        )
        writeSample(samplesDir, "2026.05.23_07.00.00_1.wav", 80)
        writeSample(samplesDir, "2026.05.23_07.00.01_1.wav", 100)
        writeSample(samplesDir, "2026.05.23_07.00.02_1.wav", 120)
        writeSample(samplesDir, "2026.05.23_07.00.03_1_unchecked.wav", 200)
        writeSample(samplesDir, "2026.05.23_07.00.04_0.wav", 10)
        writeSample(samplesDir, "2026.05.23_07.00.05_0.wav", 20)
        writeSample(samplesDir, "2026.05.23_07.00.06_0.wav", 30)

        val stats = repository.getDatasetStats()

        assertEquals(3, stats.positiveCount)
        assertEquals(3, stats.negativeCount)
        assertEquals(82..118, stats.positiveLevelRange)
        assertEquals(11..29, stats.negativeLevelRange)
    }

    @Test
    fun trainReportsProgressForProcessedFiles() {
        val samplesDir = temporaryFolder.newFolder("training-samples")
        val repository = TrainingRepository(
            samplesDir = samplesDir,
            modelFile = temporaryFolder.newFile("trained-model.json")
        )
        writeSample(samplesDir, "2026.05.23_07.00.00_1.wav", 100)
        writeSample(samplesDir, "2026.05.23_07.00.01_1.wav", 120)
        writeSample(samplesDir, "2026.05.23_07.00.02_0.wav", 0)
        writeSample(samplesDir, "2026.05.23_07.00.03_0.wav", 10)

        val progressEvents = mutableListOf<TrainingProgress>()

        runCatching {
            repository.train() { progressEvents.add(it) }
        }

        assertTrue(progressEvents.isNotEmpty())
        assertEquals(0, progressEvents.first().processedFiles)
        assertEquals(4, progressEvents.first().totalFiles)
        assertEquals(4, progressEvents.last().processedFiles)
        assertEquals(4, progressEvents.last().totalFiles)
    }

    private fun writeSample(samplesDir: File, fileName: String, amplitude: Short) {
        ManualWavFileWriter().writeWav(
            outputFile = File(samplesDir, fileName),
            pcm16MonoSamples = ShortArray(SAMPLE_COUNT) { amplitude },
            sampleRate = SAMPLE_RATE
        )
    }

    private fun writeSample(samplesDir: File, fileName: String, amplitude: Int) {
        writeSample(samplesDir, fileName, amplitude.toShort())
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLE_COUNT = 800
    }
}
