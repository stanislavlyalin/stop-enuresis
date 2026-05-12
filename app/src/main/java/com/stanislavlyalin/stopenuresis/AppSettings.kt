package com.stanislavlyalin.stopenuresis

import android.content.Context

object AppSettings {
    const val DEFAULT_VOLUME_THRESHOLD = 100
    const val DEFAULT_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT = 70
    const val DEFAULT_COOLDOWN_HOURS = 2
    const val DEFAULT_ALARM_RUSTLING_DETECTIONS_PER_MINUTE = 3

    private const val PREFS_NAME = "stop_enuresis_settings"
    private const val KEY_ABOUT_SCREEN_SEEN = "about_screen_seen"
    private const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"
    private const val KEY_VOLUME_THRESHOLD = "volume_threshold"
    private const val KEY_RUSTLING_THRESHOLD_EXCEEDANCE_PERCENT =
        "rustling_threshold_exceedance_percent"
    private const val KEY_COOLDOWN_HOURS = "cooldown_hours"
    private const val KEY_ALARM_RUSTLING_DETECTIONS_PER_MINUTE =
        "alarm_rustling_detections_per_minute"

    fun isAboutScreenSeen(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ABOUT_SCREEN_SEEN, false)
    }

    fun setAboutScreenSeen(context: Context, seen: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ABOUT_SCREEN_SEEN, seen)
            .apply()
    }

    fun isDarkThemeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_THEME_ENABLED, false)
    }

    fun setDarkThemeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME_ENABLED, enabled)
            .apply()
    }

    fun getVolumeThreshold(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_VOLUME_THRESHOLD, DEFAULT_VOLUME_THRESHOLD)
            .coerceIn(0, 200)
    }

    fun setVolumeThreshold(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOLUME_THRESHOLD, value.coerceIn(0, 200))
            .apply()
    }

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

    fun getAlarmRustlingDetectionsPerMinute(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(
                KEY_ALARM_RUSTLING_DETECTIONS_PER_MINUTE,
                DEFAULT_ALARM_RUSTLING_DETECTIONS_PER_MINUTE
            )
            .coerceIn(1, 12)
    }

    fun setAlarmRustlingDetectionsPerMinute(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ALARM_RUSTLING_DETECTIONS_PER_MINUTE, value.coerceIn(1, 12))
            .apply()
    }
}
