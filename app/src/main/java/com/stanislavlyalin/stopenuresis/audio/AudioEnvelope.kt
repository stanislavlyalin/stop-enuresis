package com.stanislavlyalin.stopenuresis.audio

object AudioEnvelope {

    const val DEFAULT_WINDOW_SECONDS = 1f

    fun smoothAbsolute(
        input: ShortArray,
        output: FloatArray,
        sampleRate: Int,
        windowSeconds: Float = DEFAULT_WINDOW_SECONDS
    ): Float {
        return smoothAbsolute(
            input = input,
            inputLength = input.size,
            output = output,
            sampleRate = sampleRate,
            windowSeconds = windowSeconds
        )
    }

    fun smoothAbsolute(
        input: ShortArray,
        inputLength: Int,
        output: FloatArray,
        sampleRate: Int,
        windowSeconds: Float = DEFAULT_WINDOW_SECONDS
    ): Float {
        require(inputLength in 0..input.size) {
            "inputLength ($inputLength) must be in 0..${input.size}"
        }
        require(output.size >= inputLength) {
            "output.size (${output.size}) must be >= inputLength ($inputLength)"
        }
        if (inputLength == 0) return 0f

        val windowSize = windowSize(windowSeconds, sampleRate)
        val window = IntArray(windowSize)
        var windowIndex = 0
        var windowLength = 0
        var windowSum = 0L
        var smoothedSum = 0.0

        for (sampleIndex in 0 until inputLength) {
            val value = abs16(input[sampleIndex])

            if (windowLength < windowSize) {
                window[windowIndex] = value
                windowLength++
                windowSum += value.toLong()
            } else {
                windowSum -= window[windowIndex].toLong()
                window[windowIndex] = value
                windowSum += value.toLong()
            }

            val smoothed = windowSum.toFloat() / windowLength.toFloat()
            output[sampleIndex] = smoothed
            smoothedSum += smoothed.toDouble()

            windowIndex++
            if (windowIndex == windowSize) windowIndex = 0
        }

        return (smoothedSum / inputLength.toDouble()).toFloat()
    }

    fun averageSmoothedAbsolute(
        input: ShortArray,
        sampleRate: Int,
        windowSeconds: Float = DEFAULT_WINDOW_SECONDS
    ): Float {
        return averageSmoothedAbsolute(
            input = input,
            inputLength = input.size,
            sampleRate = sampleRate,
            windowSeconds = windowSeconds
        )
    }

    fun averageSmoothedAbsolute(
        input: ShortArray,
        inputLength: Int,
        sampleRate: Int,
        windowSeconds: Float = DEFAULT_WINDOW_SECONDS
    ): Float {
        require(inputLength in 0..input.size) {
            "inputLength ($inputLength) must be in 0..${input.size}"
        }
        if (inputLength == 0) return 0f

        val windowSize = windowSize(windowSeconds, sampleRate)
        val window = IntArray(windowSize)
        var windowIndex = 0
        var windowLength = 0
        var windowSum = 0L
        var smoothedSum = 0.0

        for (sampleIndex in 0 until inputLength) {
            val value = abs16(input[sampleIndex])

            if (windowLength < windowSize) {
                window[windowIndex] = value
                windowLength++
                windowSum += value.toLong()
            } else {
                windowSum -= window[windowIndex].toLong()
                window[windowIndex] = value
                windowSum += value.toLong()
            }

            smoothedSum += windowSum.toDouble() / windowLength.toDouble()

            windowIndex++
            if (windowIndex == windowSize) windowIndex = 0
        }

        return (smoothedSum / inputLength.toDouble()).toFloat()
    }

    private fun windowSize(windowSeconds: Float, sampleRate: Int): Int {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        return (windowSeconds * sampleRate).toInt().coerceAtLeast(1)
    }

    private fun abs16(value: Short): Int {
        val intValue = value.toInt()
        return if (intValue >= 0) {
            intValue
        } else if (intValue == Short.MIN_VALUE.toInt()) {
            32768
        } else {
            -intValue
        }
    }
}
