package com.stanislavlyalin.stopenuresis

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionIntervalTest {
    @Test
    fun supportsSixHalfHourValuesAndDefaultsToOneHour() {
        assertEquals(60, AppSettings.DEFAULT_PROTECTION_INTERVAL_MINUTES)
        for (minutes in 30..180 step 30) {
            assertEquals(minutes, AppSettings.normalizeProtectionIntervalMinutes(minutes))
        }
    }

    @Test
    fun storedValuesAreBoundedAndAlignedToHalfHours() {
        assertEquals(30, AppSettings.normalizeProtectionIntervalMinutes(0))
        assertEquals(180, AppSettings.normalizeProtectionIntervalMinutes(360))
        assertEquals(60, AppSettings.normalizeProtectionIntervalMinutes(75))
    }
}
