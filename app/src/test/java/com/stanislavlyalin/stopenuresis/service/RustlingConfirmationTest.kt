package com.stanislavlyalin.stopenuresis.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustlingConfirmationTest {
    @Test
    fun overlappingPositiveWindowsCountAsOneEpisode() {
        val confirmation = RustlingConfirmation(3, 120_000)
        assertTrue(confirmation.accept(true, 0, true).newEpisode)
        assertFalse(confirmation.accept(true, 2_500, true).newEpisode)
        assertFalse(confirmation.accept(true, 5_000, true).newEpisode)
        assertEquals(1, confirmation.episodeCount)
    }

    @Test
    fun threeSeparatedEpisodesTriggerAlarmWithinPeriod() {
        val confirmation = RustlingConfirmation(3, 120_000)
        assertFalse(confirmation.accept(true, 0, true).alarm)
        confirmation.accept(false, 2_500, true)
        assertFalse(confirmation.accept(true, 30_000, true).alarm)
        confirmation.accept(false, 32_500, true)
        assertTrue(confirmation.accept(true, 119_999, true).alarm)
        assertEquals(3, confirmation.episodeCount)
    }

    @Test
    fun expiredPeriodResetsCountAndStartsWithNormalMode() {
        val confirmation = RustlingConfirmation(3, 120_000)
        confirmation.accept(true, 0, true)
        confirmation.accept(false, 2_500, true)
        confirmation.advance(120_000)
        assertFalse(confirmation.isConfirming)
        assertEquals(0, confirmation.episodeCount)
        assertFalse(confirmation.accept(true, 120_001, true).alarm)
        assertEquals(1, confirmation.episodeCount)
    }

    @Test
    fun protectionIntervalDoesNotStartConfirmation() {
        val confirmation = RustlingConfirmation(3, 120_000)
        assertFalse(confirmation.accept(true, 0, false).newEpisode)
        assertEquals(0, confirmation.episodeCount)
        assertFalse(confirmation.isConfirming)
        assertTrue(confirmation.accept(true, 60_000, true).newEpisode)
        assertEquals(1, confirmation.episodeCount)
    }
}
