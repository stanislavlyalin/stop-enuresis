package com.stanislavlyalin.stopenuresis

import android.content.Context

object AppSettings {
    const val DEFAULT_VOLUME_THRESHOLD = 75
    const val DEFAULT_LISTENING_START_PROTECTION_HOURS = 1
    const val DEFAULT_COOLDOWN_HOURS = 2
    const val DEFAULT_ALARM_RUSTLING_EPISODES = 3
    const val DEFAULT_CONFIRMATION_PERIOD_MINUTES = 2
    const val DEFAULT_USAGE_SAMPLE_SAVING_ENABLED = true

    private const val PREFS_NAME = "stop_enuresis_settings"
    private const val KEY_ABOUT_SCREEN_SEEN = "about_screen_seen"
    private const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"
    private const val KEY_VOLUME_THRESHOLD = "volume_threshold"
    private const val KEY_LISTENING_START_PROTECTION_HOURS = "listening_start_protection_hours"
    private const val KEY_COOLDOWN_HOURS = "cooldown_hours"
    private const val KEY_ALARM_RUSTLING_EPISODES = "alarm_rustling_episodes"
    private const val KEY_LEGACY_ALARM_RUSTLING_DETECTIONS =
        "alarm_rustling_detections_per_minute"
    private const val KEY_CONFIRMATION_PERIOD_MINUTES = "confirmation_period_minutes"
    private const val KEY_USAGE_SAMPLE_SAVING_ENABLED = "usage_sample_saving_enabled"

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

    fun getListeningStartProtectionHours(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(
                KEY_LISTENING_START_PROTECTION_HOURS,
                DEFAULT_LISTENING_START_PROTECTION_HOURS
            )
            .coerceIn(0, 6)
    }

    fun setListeningStartProtectionHours(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LISTENING_START_PROTECTION_HOURS, value.coerceIn(0, 6))
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

    fun getAlarmRustlingEpisodes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(
            KEY_ALARM_RUSTLING_EPISODES,
            prefs.getInt(KEY_LEGACY_ALARM_RUSTLING_DETECTIONS, DEFAULT_ALARM_RUSTLING_EPISODES)
        )
            .coerceIn(1, 12)
    }

    fun setAlarmRustlingEpisodes(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ALARM_RUSTLING_EPISODES, value.coerceIn(1, 12))
            .apply()
    }

    fun getConfirmationPeriodMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONFIRMATION_PERIOD_MINUTES, DEFAULT_CONFIRMATION_PERIOD_MINUTES)
            .coerceIn(1, 10)
    }

    fun setConfirmationPeriodMinutes(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CONFIRMATION_PERIOD_MINUTES, value.coerceIn(1, 10))
            .apply()
    }

    fun isUsageSampleSavingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                KEY_USAGE_SAMPLE_SAVING_ENABLED,
                DEFAULT_USAGE_SAMPLE_SAVING_ENABLED
            )
    }

    fun setUsageSampleSavingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USAGE_SAMPLE_SAVING_ENABLED, enabled)
            .apply()
    }
}
