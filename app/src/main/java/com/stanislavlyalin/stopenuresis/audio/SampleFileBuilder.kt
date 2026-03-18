package com.stanislavlyalin.stopenuresis.audio

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SampleFileBuilder(
    private val fragmentSizeSamples: Int,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int,
    private val samplesDir: File,
    private val wavFileWriter: WavFileWriter,
    private val onSampleFileWritten: (File) -> Unit = {}
) {

    private var threshold: Float = 0f
    private var thresholdExceedancePercent: Int = 70

    private val fragmentBuffer = ShortArray(fragmentSizeSamples)
    private var fragmentBufferSize = 0
    private var aboveThresholdSamples = 0
    private var negativeFragmentCounter = 0

    init {
        require(channelCount == 1) { "Only mono is supported for now" }
        require(bitsPerSample == 16) { "Only 16-bit PCM is supported for now" }
        require(fragmentSizeSamples > 0) { "fragmentSizeSamples must be > 0" }

        samplesDir.mkdirs()
    }

    fun setThreshold(threshold: Float) {
        this.threshold = threshold
    }

    fun setThresholdExceedancePercent(percent: Int) {
        thresholdExceedancePercent = percent.coerceIn(0, 100)
    }

    fun reset() {
        fragmentBufferSize = 0
        aboveThresholdSamples = 0
        negativeFragmentCounter = 0
    }

    fun addSamples(
        pcm16MonoSamples: ShortArray,
        envelopeSamples: FloatArray,
        readCount: Int
    ) {
        if (readCount <= 0) return
        require(readCount <= pcm16MonoSamples.size) {
            "readCount ($readCount) must be <= pcm16MonoSamples.size (${pcm16MonoSamples.size})"
        }
        require(readCount <= envelopeSamples.size) {
            "readCount ($readCount) must be <= envelopeSamples.size (${envelopeSamples.size})"
        }

        for (i in 0 until readCount) {
            fragmentBuffer[fragmentBufferSize] = pcm16MonoSamples[i]
            if (envelopeSamples[i] >= threshold) aboveThresholdSamples++
            fragmentBufferSize++

            if (fragmentBufferSize == fragmentSizeSamples) {
                handleCompletedFragment()
                fragmentBufferSize = 0
                aboveThresholdSamples = 0
            }
        }
    }

    private fun handleCompletedFragment() {
        val exceedancePercent = aboveThresholdSamples * 100f / fragmentSizeSamples.toFloat()
        val hasRustling = exceedancePercent >= thresholdExceedancePercent.toFloat()

        if (hasRustling) {
            dispatchWrite(
                pcm16MonoSamples = fragmentBuffer.copyOf(),
                classLabel = 1,
                unchecked = true
            )
            return
        }

        negativeFragmentCounter++
        if (negativeFragmentCounter % NEGATIVE_FRAGMENT_SAVE_INTERVAL == 0) {
            dispatchWrite(
                pcm16MonoSamples = fragmentBuffer.copyOf(),
                classLabel = 0,
                unchecked = false
            )
        }
    }

    private fun dispatchWrite(
        pcm16MonoSamples: ShortArray,
        classLabel: Int,
        unchecked: Boolean
    ) {
        if (pcm16MonoSamples.isEmpty()) return

        val fileName = buildFileName(classLabel, unchecked)
        val outputFile = File(samplesDir, fileName)

        thread(
            start = true,
            isDaemon = true,
            name = "wav-writer-$classLabel"
        ) {
            wavFileWriter.writeWav(
                outputFile = outputFile,
                pcm16MonoSamples = pcm16MonoSamples,
                sampleRate = sampleRate
            )
            onSampleFileWritten(outputFile)
        }
    }

    private fun buildFileName(classLabel: Int, unchecked: Boolean): String {
        val formatter = SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.getDefault())
        val timestamp = formatter.format(Date())
        val suffix = if (unchecked) "_unchecked" else ""
        return "${timestamp}_${classLabel}${suffix}.wav"
    }

    private companion object {
        const val NEGATIVE_FRAGMENT_SAVE_INTERVAL = 60
    }
}
