package com.stanislavlyalin.stopenuresis.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionWindowStreamTest {
    @Test
    fun firstWindowIsNormalThenWindowsOverlapEvenAcrossReads() {
        val windows = mutableListOf<List<Short>>()
        val stream = DetectionWindowStream(4)
        val onWindow: (ShortArray) -> Int = { window ->
            windows.add(window.toList())
            if (windows.size == 1) 2 else 4
        }

        stream.append(shortArrayOf(1, 2, 3), 3, onWindow)
        assertEquals(emptyList<List<Short>>(), windows)
        stream.append(shortArrayOf(4, 5, 6, 7, 8, 9, 10), 7, onWindow)

        assertEquals(
            listOf(
                listOf<Short>(1, 2, 3, 4),
                listOf<Short>(3, 4, 5, 6),
                listOf<Short>(7, 8, 9, 10)
            ),
            windows
        )
    }

    @Test
    fun soundAtNormalWindowBoundaryAppearsInOverlappingWindow() {
        val windows = mutableListOf<List<Short>>()
        val stream = DetectionWindowStream(4)
        val onWindow: (ShortArray) -> Int = { window ->
            windows.add(window.toList())
            if (windows.size == 2) 2 else 4
        }
        stream.append(shortArrayOf(0, 0, 0, 1, 1, 0, 0, 0, 0, 0), 10, onWindow)

        assertEquals(listOf<Short>(0, 0, 0, 1), windows[0])
        assertEquals(listOf<Short>(1, 0, 0, 0), windows[1])
        assertEquals(listOf<Short>(0, 0, 0, 0), windows[2])
    }
}
