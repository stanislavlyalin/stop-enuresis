package com.stanislavlyalin.stopenuresis.audio

class StreamingMovingAverageSmoother(
    windowSeconds: Float,
    sampleRate: Int
) {
    private val windowSize = (windowSeconds * sampleRate).toInt().coerceAtLeast(1)
    private val buffer = IntArray(windowSize)

    private var index = 0
    private var len = 0
    private var sum = 0L

    @Suppress("NOTHING_TO_INLINE")
    private inline fun abs16(value: Short): Int {
        val x = value.toInt()
        return if (x >= 0) x else if (x == Short.MIN_VALUE.toInt()) 32768 else -x
    }

    fun smooth(input: ShortArray, output: FloatArray) {
        smooth(input, input.size, output)
    }

    fun smooth(input: ShortArray, inputLength: Int, output: FloatArray) {
        require(inputLength in 0..input.size) {
            "inputLength ($inputLength) must be in 0..${input.size}"
        }
        require(output.size >= inputLength) {
            "output.size (${output.size}) must be >= inputLength ($inputLength)"
        }

        for (n in 0 until inputLength) {
            val value = abs16(input[n])

            if (len < windowSize) {
                buffer[index] = value
                sum += value.toLong()
                len++
            } else {
                sum -= buffer[index].toLong()
                buffer[index] = value
                sum += value.toLong()
            }

            output[n] = sum.toFloat() / len.toFloat()

            index++
            if (index == windowSize) index = 0
        }
    }

    fun reset() {
        index = 0
        len = 0
        sum = 0L
    }
}
