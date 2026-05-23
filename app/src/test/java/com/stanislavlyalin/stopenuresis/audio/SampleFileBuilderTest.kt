package com.stanislavlyalin.stopenuresis.audio

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleFileBuilderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun addSamplesDoesNotSaveQuietWindowAfterLoudWindow() {
        val writer = CapturingWavFileWriter()
        val firstWrite = CountDownLatch(1)
        val builder = SampleFileBuilder(
            fragmentSizeSamples = 10,
            sampleRate = 10,
            channelCount = 1,
            bitsPerSample = 16,
            samplesDir = temporaryFolder.newFolder(),
            wavFileWriter = writer,
            onSampleFileWritten = { firstWrite.countDown() }
        )
        builder.setThreshold(40f)

        builder.addSamples(ShortArray(10) { 100 }, 10)
        assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
        assertEquals(1, writer.writtenSamples.size)

        builder.addSamples(ShortArray(10) { 0 }, 10)
        Thread.sleep(200)

        assertEquals(1, writer.writtenSamples.size)
    }

    @Test
    fun savedRustlingWindowAverageMeetsThresholdWithSameEnvelopeAlgorithm() {
        val writer = CapturingWavFileWriter()
        val firstWrite = CountDownLatch(1)
        val builder = SampleFileBuilder(
            fragmentSizeSamples = 10,
            sampleRate = 10,
            channelCount = 1,
            bitsPerSample = 16,
            samplesDir = temporaryFolder.newFolder(),
            wavFileWriter = writer,
            onSampleFileWritten = { firstWrite.countDown() }
        )
        builder.setThreshold(60f)

        builder.addSamples(ShortArray(10) { 80 }, 10)

        assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
        assertEquals(1, writer.writtenSamples.size)
        val savedSamples = writer.writtenSamples.single()
        val savedAverage = AudioEnvelope.averageSmoothedAbsolute(
            input = savedSamples,
            sampleRate = 10
        )
        assertTrue(savedAverage >= 60f)
    }

    private class CapturingWavFileWriter : WavFileWriter {

        val writtenSamples = CopyOnWriteArrayList<ShortArray>()

        override fun writeWav(
            outputFile: File,
            pcm16MonoSamples: ShortArray,
            sampleRate: Int
        ) {
            writtenSamples.add(pcm16MonoSamples.copyOf())
        }
    }
}
