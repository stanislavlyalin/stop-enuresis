package com.stanislavlyalin.stopenuresis.audio

class RecentAudioWindow(
    private val capacitySamples: Int
) {

    private val ringBuffer: ShortArray
    private var writeIndex = 0
    private var size = 0

    val isFull: Boolean
        get() = size == capacitySamples

    init {
        require(capacitySamples > 0) { "capacitySamples must be > 0" }
        ringBuffer = ShortArray(capacitySamples)
    }

    fun clear() {
        writeIndex = 0
        size = 0
    }

    fun append(samples: ShortArray, count: Int) {
        if (count <= 0) return
        require(count <= samples.size) {
            "count ($count) must be <= samples.size (${samples.size})"
        }

        val startIndex = (count - capacitySamples).coerceAtLeast(0)
        for (sampleIndex in startIndex until count) {
            ringBuffer[writeIndex] = samples[sampleIndex]
            writeIndex++
            if (writeIndex == capacitySamples) writeIndex = 0
            if (size < capacitySamples) size++
        }
    }

    fun copySamples(): ShortArray {
        require(isFull) { "Audio window is not full yet" }

        val samples = ShortArray(capacitySamples)
        val firstPartLength = capacitySamples - writeIndex
        System.arraycopy(ringBuffer, writeIndex, samples, 0, firstPartLength)
        if (writeIndex > 0) {
            System.arraycopy(ringBuffer, 0, samples, firstPartLength, writeIndex)
        }
        return samples
    }
}
