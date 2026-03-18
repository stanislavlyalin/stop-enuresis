package com.stanislavlyalin.stopenuresis.audio

import java.io.File

interface WavFileWriter {
    fun writeWav(
        outputFile: File,
        pcm16MonoSamples: ShortArray,
        sampleRate: Int
    )
}
