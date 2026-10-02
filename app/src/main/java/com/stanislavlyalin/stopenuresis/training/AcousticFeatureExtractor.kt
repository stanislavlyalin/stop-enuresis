package com.stanislavlyalin.stopenuresis.training

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Признаки формы спектра и его изменения во времени после нормализации громкости. */
class AcousticFeatureExtractor(
    private val coefficientCount: Int = 13,
    private val melFilterCount: Int = 26
) {
    fun extractFeatures(audio: WavAudio): DoubleArray {
        require(coefficientCount >= 6) { "At least six MFCC coefficients are required" }
        val frameSize = (audio.sampleRate * FRAME_SECONDS).toInt().coerceAtLeast(256)
        val hopSize = (audio.sampleRate * HOP_SECONDS).toInt().coerceAtLeast(80)
        val fftSize = nextPowerOfTwo(frameSize)
        val frameCount = if (audio.samples.size < frameSize) 1 else 1 + (audio.samples.size - frameSize) / hopSize
        val normalizedSamples = normalizeLoudness(audio.samples)
        val frequencies = DoubleArray(fftSize / 2 + 1) { index -> index.toDouble() * audio.sampleRate / fftSize }
        val filters = buildMelFilters(audio.sampleRate, fftSize)
        val window = hammingWindow(frameSize)

        val mfcc1 = DoubleArray(frameCount)
        val mfcc5 = DoubleArray(frameCount)
        val bandwidth = DoubleArray(frameCount)
        val centroid = DoubleArray(frameCount)
        val entropy = DoubleArray(frameCount)
        val flatness = DoubleArray(frameCount)
        val highRatio = DoubleArray(frameCount)
        val lowRatio = DoubleArray(frameCount)
        val peakProminence = DoubleArray(frameCount)
        val rolloff85 = DoubleArray(frameCount)
        val rolloff95 = DoubleArray(frameCount)
        val rms = DoubleArray(frameCount)
        val zeroCrossingRate = DoubleArray(frameCount)
        val flux = DoubleArray((frameCount - 1).coerceAtLeast(0))
        var previousSpectrum: DoubleArray? = null
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)

        for (frameIndex in 0 until frameCount) {
            real.fill(0.0)
            imaginary.fill(0.0)
            val start = frameIndex * hopSize
            var squareSum = 0.0
            var crossings = 0
            var previousNegative = false
            for (index in 0 until frameSize) {
                val sampleIndex = start + index
                val sample = if (sampleIndex < normalizedSamples.size) normalizedSamples[sampleIndex] else 0f
                real[index] = sample.toDouble() * window[index]
                squareSum += sample * sample
                val negative = sample < 0f
                if (index > 0 && negative != previousNegative) crossings++
                previousNegative = negative
            }
            rms[frameIndex] = sqrt(squareSum / frameSize)
            zeroCrossingRate[frameIndex] = crossings.toDouble() / (frameSize - 1).coerceAtLeast(1)

            fft(real, imaginary)
            val power = DoubleArray(frequencies.size) { index -> real[index] * real[index] + imaginary[index] * imaginary[index] }
            val powerSum = power.sum().coerceAtLeast(EPSILON)
            val normalizedSpectrum = DoubleArray(power.size) { index -> power[index] / powerSum }
            centroid[frameIndex] = normalizedSpectrum.indices.sumOf { index -> normalizedSpectrum[index] * frequencies[index] }
            bandwidth[frameIndex] = sqrt(normalizedSpectrum.indices.sumOf { index ->
                normalizedSpectrum[index] * (frequencies[index] - centroid[frameIndex]).pow(2.0)
            })
            entropy[frameIndex] = -normalizedSpectrum.sumOf { value -> value * ln(value.coerceAtLeast(EPSILON)) }
            flatness[frameIndex] = expAverageLog(power) / (powerSum / power.size).coerceAtLeast(EPSILON)
            peakProminence[frameIndex] = power.maxOrNull()!!.div(powerSum / power.size).coerceAtLeast(EPSILON)
            rolloff85[frameIndex] = rolloffFrequency(power, frequencies, 0.85, powerSum)
            rolloff95[frameIndex] = rolloffFrequency(power, frequencies, 0.95, powerSum)
            lowRatio[frameIndex] = bandRatio(power, frequencies, 250.0, 1_000.0, powerSum)
            highRatio[frameIndex] = bandRatio(power, frequencies, 3_000.0, 8_000.0, powerSum)
            previousSpectrum?.let { previous ->
                flux[frameIndex - 1] = sqrt(normalizedSpectrum.indices.sumOf { index ->
                    (normalizedSpectrum[index] - previous[index]).pow(2.0)
                })
            }
            previousSpectrum = normalizedSpectrum

            val logMelEnergies = DoubleArray(melFilterCount)
            for (filterIndex in 0 until melFilterCount) {
                var energy = 0.0
                val filter = filters[filterIndex]
                for (index in filter.indices) energy += power[index] * filter[index]
                logMelEnergies[filterIndex] = ln(energy.coerceAtLeast(EPSILON))
            }
            mfcc1[frameIndex] = mfcc(logMelEnergies, 1)
            mfcc5[frameIndex] = mfcc(logMelEnergies, 5)
        }

        return doubleArrayOf(
            mean(mfcc1), stdDev(mfcc1), mean(mfcc5),
            mean(bandwidth), percentile(bandwidth, 0.05), percentile(bandwidth, 0.95), stdDev(bandwidth),
            mean(centroid), percentile(centroid, 0.95), stdDev(centroid),
            mean(entropy), percentile(entropy, 0.95), stdDev(entropy),
            mean(flatness), percentile(flatness, 0.05), percentile(flatness, 0.95), stdDev(flatness),
            stdDev(flux), mean(highRatio), mean(lowRatio), mean(peakProminence), percentile(peakProminence, 0.05),
            mean(rolloff85), percentile(rolloff85, 0.95), stdDev(rolloff85),
            mean(rolloff95), percentile(rolloff95, 0.95), stdDev(rolloff95),
            mean(rms), stdDev(rms), mean(zeroCrossingRate), stdDev(zeroCrossingRate)
        )
    }

    private fun normalizeLoudness(samples: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (sample in samples) sumSquares += sample * sample
        val rms = sqrt(sumSquares / samples.size.coerceAtLeast(1))
        if (rms < MIN_NORMALIZABLE_RMS) return samples
        val gain = TARGET_RMS / rms
        return FloatArray(samples.size) { index -> (samples[index] * gain).toFloat() }
    }

    private fun bandRatio(power: DoubleArray, frequencies: DoubleArray, low: Double, high: Double, total: Double): Double {
        var result = 0.0
        for (index in power.indices) if (frequencies[index] >= low && frequencies[index] < high) result += power[index]
        return result / total
    }

    private fun rolloffFrequency(power: DoubleArray, frequencies: DoubleArray, ratio: Double, total: Double): Double {
        var accumulated = 0.0
        for (index in power.indices) {
            accumulated += power[index]
            if (accumulated >= total * ratio) return frequencies[index]
        }
        return frequencies.last()
    }

    private fun expAverageLog(values: DoubleArray): Double = kotlin.math.exp(values.sumOf { ln(it.coerceAtLeast(EPSILON)) } / values.size)

    private fun mfcc(logMelEnergies: DoubleArray, coefficientIndex: Int): Double = logMelEnergies.indices.sumOf { filterIndex ->
        logMelEnergies[filterIndex] * cos(PI * coefficientIndex * (filterIndex + 0.5) / melFilterCount)
    }

    private fun mean(values: DoubleArray): Double = values.average()

    private fun stdDev(values: DoubleArray): Double {
        val average = mean(values)
        return sqrt(values.sumOf { value -> (value - average).pow(2.0) } / values.size.coerceAtLeast(1))
    }

    private fun percentile(values: DoubleArray, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val position = (sorted.lastIndex * percentile).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = floor(position).toInt()
        val upper = kotlin.math.ceil(position).toInt()
        return if (lower == upper) sorted[lower] else sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun buildMelFilters(sampleRate: Int, fftSize: Int): Array<DoubleArray> {
        val spectrumSize = fftSize / 2 + 1
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(melFilterCount + 2) { index -> lowMel + (highMel - lowMel) * index / (melFilterCount + 1).toDouble() }
        val bins = melPoints.map { mel -> floor((fftSize + 1) * melToHz(mel) / sampleRate).toInt().coerceIn(0, spectrumSize - 1) }
        return Array(melFilterCount) { filterIndex ->
            DoubleArray(spectrumSize).also { filter ->
                val left = bins[filterIndex]
                val center = bins[filterIndex + 1]
                val right = bins[filterIndex + 2]
                for (index in left until center) if (center > left) filter[index] = (index - left).toDouble() / (center - left)
                for (index in center until right) if (right > center) filter[index] = (right - index).toDouble() / (right - center)
            }
        }
    }

    private fun hammingWindow(size: Int): DoubleArray = DoubleArray(size) { index -> 0.54 - 0.46 * cos(2.0 * PI * index / (size - 1)) }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if (index < reversed) {
                val realValue = real[index]; real[index] = real[reversed]; real[reversed] = realValue
                val imaginaryValue = imaginary[index]; imaginary[index] = imaginary[reversed]; imaginary[reversed] = imaginaryValue
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val lengthReal = cos(angle)
            val lengthImaginary = sin(angle)
            var start = 0
            while (start < size) {
                var weightReal = 1.0
                var weightImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * weightReal - imaginary[odd] * weightImaginary
                    val oddImaginary = real[odd] * weightImaginary + imaginary[odd] * weightReal
                    real[odd] = real[even] - oddReal; imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal; imaginary[even] += oddImaginary
                    val nextWeightReal = weightReal * lengthReal - weightImaginary * lengthImaginary
                    weightImaginary = weightReal * lengthImaginary + weightImaginary * lengthReal
                    weightReal = nextWeightReal
                }
                start += length
            }
            length = length shl 1
        }
    }

    companion object {
        const val FEATURE_COUNT = 32
        private const val FRAME_SECONDS = 0.025
        private const val HOP_SECONDS = 0.010
        private const val TARGET_RMS = 0.1
        private const val MIN_NORMALIZABLE_RMS = 1e-6
        private const val EPSILON = 1e-12
    }
}
