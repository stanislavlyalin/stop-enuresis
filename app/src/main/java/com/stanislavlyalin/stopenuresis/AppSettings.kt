package com.stanislavlyalin.stopenuresis

import android.content.Context

object AppSettings {
    const val DEFAULT_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT = 70
    const val DEFAULT_COOLDOWN_HOURS = 2

    private const val PREFS_NAME = "stop_enuresis_settings"
    private const val KEY_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT =
        "rustling_threshold_exceedance_percent"
    private const val KEY_COOLDOWN_HOURS = "cooldown_hours"

    fun getRustlingThresholdExceedancePercent(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(
                KEY_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT,
                DEFAULT_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT
            )
    }

    fun setRustlingThresholdExceedancePercent(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT, value.coerceIn(0, 100))
            .apply()
    }

    fun getCooldownHours(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_COOLDOWN_HOURS, DEFAULT_COOLDOWN_HOURS)
    }

    fun setCooldownHours(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COOLDOWN_HOURS, value.coerceIn(0, 6))
            .apply()
    }
}
