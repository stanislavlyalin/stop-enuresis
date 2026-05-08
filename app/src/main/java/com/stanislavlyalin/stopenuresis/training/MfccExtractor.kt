package com.stanislavlyalin.stopenuresis.training

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MfccExtractor(
    private val coefficientCount: Int = 13,
    private val melFilterCount: Int = 26
) {
    fun extractFeatures(audio: WavAudio): DoubleArray {
        val frameSize = (audio.sampleRate * 0.025).toInt().coerceAtLeast(256)
        val hopSize = (audio.sampleRate * 0.010).toInt().coerceAtLeast(80)
        val fftSize = nextPowerOfTwo(frameSize)
        val filters = buildMelFilters(audio.sampleRate, fftSize)
        val window = hammingWindow(frameSize)
        val frameCount = if (audio.samples.size < frameSize) {
            1
        } else {
            1 + (audio.samples.size - frameSize) / hopSize
        }

        val sums = DoubleArray(coefficientCount)
        val squareSums = DoubleArray(coefficientCount)
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)

        for (frameIndex in 0 until frameCount) {
            real.fill(0.0)
            imag.fill(0.0)
            val start = frameIndex * hopSize
            for (i in 0 until frameSize) {
                val sampleIndex = start + i
                val sample = if (sampleIndex < audio.samples.size) audio.samples[sampleIndex] else 0f
                real[i] = sample.toDouble() * window[i]
            }

            fft(real, imag)
            val powerSpectrum = DoubleArray(fftSize / 2 + 1)
            for (i in powerSpectrum.indices) {
                powerSpectrum[i] = real[i] * real[i] + imag[i] * imag[i]
            }

            val logMelEnergies = DoubleArray(melFilterCount)
            for (filterIndex in 0 until melFilterCount) {
                var energy = 0.0
                val filter = filters[filterIndex]
                for (i in filter.indices) {
                    energy += powerSpectrum[i] * filter[i]
                }
                logMelEnergies[filterIndex] = ln(energy.coerceAtLeast(1e-12))
            }

            for (coefficientIndex in 0 until coefficientCount) {
                var value = 0.0
                for (filterIndex in 0 until melFilterCount) {
                    value += logMelEnergies[filterIndex] *
                        cos(PI * coefficientIndex * (filterIndex + 0.5) / melFilterCount)
                }
                sums[coefficientIndex] += value
                squareSums[coefficientIndex] += value * value
            }
        }

        val features = DoubleArray(coefficientCount * 2)
        for (i in 0 until coefficientCount) {
            val mean = sums[i] / frameCount.toDouble()
            val variance = squareSums[i] / frameCount.toDouble() - mean * mean
            features[i] = mean
            features[i + coefficientCount] = sqrt(variance.coerceAtLeast(0.0))
        }
        return features
    }

    private fun buildMelFilters(sampleRate: Int, fftSize: Int): Array<DoubleArray> {
        val spectrumSize = fftSize / 2 + 1
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(melFilterCount + 2) { index ->
            lowMel + (highMel - lowMel) * index / (melFilterCount + 1).toDouble()
        }
        val bins = melPoints.map { mel ->
            floor((fftSize + 1) * melToHz(mel) / sampleRate.toDouble())
                .toInt()
                .coerceIn(0, spectrumSize - 1)
        }

        return Array(melFilterCount) { filterIndex ->
            val filter = DoubleArray(spectrumSize)
            val left = bins[filterIndex]
            val center = bins[filterIndex + 1]
            val right = bins[filterIndex + 2]

            if (center > left) {
                for (i in left until center) {
                    filter[i] = (i - left).toDouble() / (center - left).toDouble()
                }
            }
            if (right > center) {
                for (i in center until right) {
                    filter[i] = (right - i).toDouble() / (right - center).toDouble()
                }
            }
            filter
        }
    }

    private fun hammingWindow(size: Int): DoubleArray {
        return DoubleArray(size) { i ->
            0.54 - 0.46 * cos(2.0 * PI * i / (size - 1).toDouble())
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len.toDouble()
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            var i = 0
            while (i < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until len / 2) {
                    val evenIndex = i + k
                    val oddIndex = i + k + len / 2
                    val oddReal = real[oddIndex] * wReal - imag[oddIndex] * wImag
                    val oddImag = real[oddIndex] * wImag + imag[oddIndex] * wReal

                    real[oddIndex] = real[evenIndex] - oddReal
                    imag[oddIndex] = imag[evenIndex] - oddImag
                    real[evenIndex] += oddReal
                    imag[evenIndex] += oddImag

                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                i += len
            }
            len = len shl 1
        }
    }
}
