package com.stanislavlyalin.stopenuresis

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionSettingsTest {
    @Test
    fun oldIntervalsDoNotOverrideNewDefaultAndNewValuePersists() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("test_protection_$name", mode)
        }
        val prefs = context.getSharedPreferences("stop_enuresis_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            prefs.edit().putInt("listening_start_protection_hours", 3)
                .putInt("cooldown_hours", 2).commit()
            assertEquals(60, AppSettings.getProtectionIntervalMinutes(context))
            AppSettings.setProtectionIntervalMinutes(context, 90)
            assertEquals(90, AppSettings.getProtectionIntervalMinutes(context))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
