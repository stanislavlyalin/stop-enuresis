package com.stanislavlyalin.stopenuresis.audio

/** Подаёт полные окна; следующий шаг выбирается после обработки текущего окна. */
class DetectionWindowStream(private val windowSizeSamples: Int) {
    private val window = RecentAudioWindow(windowSizeSamples)
    private var samplesUntilNextWindow = windowSizeSamples

    fun append(samples: ShortArray, count: Int, onWindow: (ShortArray) -> Int) {
        require(count in 0..samples.size)
        var offset = 0
        while (offset < count) {
            val partSize = minOf(count - offset, samplesUntilNextWindow)
            window.append(samples, offset, partSize)
            offset += partSize
            samplesUntilNextWindow -= partSize
            if (samplesUntilNextWindow == 0) {
                samplesUntilNextWindow = onWindow(window.copySamples())
                require(samplesUntilNextWindow in 1..windowSizeSamples)
            }
        }
    }
}
