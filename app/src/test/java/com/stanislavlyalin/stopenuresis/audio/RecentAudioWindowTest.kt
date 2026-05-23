package com.stanislavlyalin.stopenuresis.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAudioWindowTest {

    @Test
    fun copySamplesReturnsLastSamplesInChronologicalOrder() {
        val window = RecentAudioWindow(capacitySamples = 5)

        window.append(shortArrayOf(1, 2, 3), 3)
        assertFalse(window.isFull)

        window.append(shortArrayOf(4, 5, 6, 7), 4)

        assertTrue(window.isFull)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), window.copySamples())
    }

    @Test
    fun appendKeepsOnlyLastCapacitySamplesWhenBlockIsLargerThanWindow() {
        val window = RecentAudioWindow(capacitySamples = 3)

        window.append(shortArrayOf(1, 2, 3, 4, 5), 5)

        assertTrue(window.isFull)
        assertArrayEquals(shortArrayOf(3, 4, 5), window.copySamples())
    }
}
