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
    private val saveSilenceSamples: Boolean = true,
    private val onSampleFileWritten: (File) -> Unit = {}
) {

    private var threshold: Float = 0f

    private val recentAudioWindow = RecentAudioWindow(fragmentSizeSamples)
    private var samplesSinceLastRustlingWrite = fragmentSizeSamples
    private var samplesSinceLastSilenceDecision = fragmentSizeSamples
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

    fun reset() {
        recentAudioWindow.clear()
        samplesSinceLastRustlingWrite = fragmentSizeSamples
        samplesSinceLastSilenceDecision = fragmentSizeSamples
        negativeFragmentCounter = 0
    }

    fun addSamples(
        pcm16MonoSamples: ShortArray,
        readCount: Int
    ) {
        if (readCount <= 0) return
        require(readCount <= pcm16MonoSamples.size) {
            "readCount ($readCount) must be <= pcm16MonoSamples.size (${pcm16MonoSamples.size})"
        }

        recentAudioWindow.append(pcm16MonoSamples, readCount)
        samplesSinceLastRustlingWrite =
            (samplesSinceLastRustlingWrite + readCount).coerceAtMost(fragmentSizeSamples)
        samplesSinceLastSilenceDecision =
            (samplesSinceLastSilenceDecision + readCount).coerceAtMost(fragmentSizeSamples)

        if (recentAudioWindow.isFull) {
            handleCurrentFragment()
        }
    }

    private fun handleCurrentFragment() {
        val fragment = recentAudioWindow.copySamples()
        val averageEnvelope = AudioEnvelope.averageSmoothedAbsolute(
            input = fragment,
            sampleRate = sampleRate
        )
        val hasRustling = averageEnvelope >= threshold

        if (hasRustling) {
            samplesSinceLastSilenceDecision = 0
            if (samplesSinceLastRustlingWrite >= fragmentSizeSamples) {
                dispatchWrite(
                    pcm16MonoSamples = fragment,
                    classLabel = 1,
                    unchecked = true
                )
                samplesSinceLastRustlingWrite = 0
            }
            return
        }

        if (!saveSilenceSamples) return

        if (samplesSinceLastSilenceDecision >= fragmentSizeSamples) {
            negativeFragmentCounter++
            samplesSinceLastSilenceDecision = 0
            if (negativeFragmentCounter % NEGATIVE_FRAGMENT_SAVE_INTERVAL == 0) {
                dispatchWrite(
                    pcm16MonoSamples = fragment,
                    classLabel = 0,
                    unchecked = false
                )
            }
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
