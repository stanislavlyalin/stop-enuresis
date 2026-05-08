package com.stanislavlyalin.stopenuresis.training

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavAudio(
    val sampleRate: Int,
    val samples: FloatArray
)

object WavReader {

    fun readPcm16Mono(file: File): WavAudio {
        val bytes = file.readBytes()
        if (bytes.size < 44) throw IOException("WAV file is too small: ${file.name}")
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") {
            throw IOException("Unsupported WAV RIFF header: ${file.name}")
        }
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("Unsupported WAV WAVE header: ${file.name}")
        }

        var offset = 12
        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readIntLe(bytes, offset + 4)
            val chunkDataOffset = offset + 8
            if (chunkDataOffset + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> {
                    val audioFormat = readShortLe(bytes, chunkDataOffset).toInt()
                    channelCount = readShortLe(bytes, chunkDataOffset + 2).toInt()
                    sampleRate = readIntLe(bytes, chunkDataOffset + 4)
                    bitsPerSample = readShortLe(bytes, chunkDataOffset + 14).toInt()
                    if (audioFormat != 1) {
                        throw IOException("Only PCM WAV is supported: ${file.name}")
                    }
                }

                "data" -> {
                    dataOffset = chunkDataOffset
                    dataSize = chunkSize
                }
            }

            offset = chunkDataOffset + chunkSize + chunkSize % 2
        }

        if (sampleRate <= 0 || dataOffset < 0 || dataSize <= 0) {
            throw IOException("Invalid WAV file: ${file.name}")
        }
        if (channelCount != 1 || bitsPerSample != 16) {
            throw IOException("Only 16-bit mono WAV is supported: ${file.name}")
        }

        val sampleCount = dataSize / 2
        val samples = FloatArray(sampleCount)
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = buffer.short.toFloat() / Short.MAX_VALUE.toFloat()
        }
        return WavAudio(sampleRate, samples)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(bytes: ByteArray, offset: Int): Short {
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return value.toShort()
    }
}
