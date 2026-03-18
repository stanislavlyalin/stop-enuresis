package com.stanislavlyalin.stopenuresis.audio

import java.io.File
import java.io.FileOutputStream

class ManualWavFileWriter : WavFileWriter {

    override fun writeWav(
        outputFile: File,
        pcm16MonoSamples: ShortArray,
        sampleRate: Int
    ) {
        outputFile.parentFile?.mkdirs()

        val numChannels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val audioDataSize = pcm16MonoSamples.size * bytesPerSample
        val byteRate = sampleRate * numChannels * bytesPerSample
        val blockAlign = numChannels * bytesPerSample
        val chunkSize = 36 + audioDataSize

        FileOutputStream(outputFile).use { fos ->
            writeString(fos, "RIFF")
            writeIntLE(fos, chunkSize)
            writeString(fos, "WAVE")

            writeString(fos, "fmt ")
            writeIntLE(fos, 16) // PCM subchunk size
            writeShortLE(fos, 1.toShort()) // PCM format
            writeShortLE(fos, numChannels.toShort())
            writeIntLE(fos, sampleRate)
            writeIntLE(fos, byteRate)
            writeShortLE(fos, blockAlign.toShort())
            writeShortLE(fos, bitsPerSample.toShort())

            writeString(fos, "data")
            writeIntLE(fos, audioDataSize)

            for (sample in pcm16MonoSamples) {
                writeShortLE(fos, sample)
            }
        }
    }

    private fun writeString(fos: FileOutputStream, value: String) {
        fos.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeIntLE(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
        fos.write((value shr 16) and 0xFF)
        fos.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(fos: FileOutputStream, value: Short) {
        val intValue = value.toInt()
        fos.write(intValue and 0xFF)
        fos.write((intValue shr 8) and 0xFF)
    }
}
