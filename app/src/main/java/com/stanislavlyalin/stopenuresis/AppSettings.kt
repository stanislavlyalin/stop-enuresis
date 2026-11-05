package com.stanislavlyalin.stopenuresis

import android.content.Context

object AppSettings {
    const val DEFAULT_VOLUME_THRESHOLD = 75
    const val DEFAULT_PROTECTION_INTERVAL_MINUTES = 60
    const val PROTECTION_INTERVAL_STEP_MINUTES = 30
    const val MAX_PROTECTION_INTERVAL_MINUTES = 180
    const val DEFAULT_ALARM_RUSTLING_EPISODES = 3
    const val DEFAULT_CONFIRMATION_PERIOD_MINUTES = 2
    const val DEFAULT_USAGE_SAMPLE_SAVING_ENABLED = true

    private const val PREFS_NAME = "stop_enuresis_settings"
    private const val KEY_ABOUT_SCREEN_SEEN = "about_screen_seen"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"
    private const val KEY_VOLUME_THRESHOLD = "volume_threshold"
    private const val KEY_PROTECTION_INTERVAL_MINUTES = "protection_interval_minutes"
    private const val KEY_ALARM_RUSTLING_EPISODES = "alarm_rustling_episodes"
    private const val KEY_LEGACY_ALARM_RUSTLING_DETECTIONS =
        "alarm_rustling_detections_per_minute"
    private const val KEY_CONFIRMATION_PERIOD_MINUTES = "confirmation_period_minutes"
    private const val KEY_USAGE_SAMPLE_SAVING_ENABLED = "usage_sample_saving_enabled"

    fun isOnboardingCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Пользователи прежних версий уже знакомились с приложением через "О программе".
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, prefs.getBoolean(KEY_ABOUT_SCREEN_SEEN, false))
    }

    fun completeOnboarding(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
    }

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString("theme_mode", null)
        return ThemeMode.entries.firstOrNull { it.name == stored }
            ?: if (prefs.contains(KEY_DARK_THEME_ENABLED)) {
                if (prefs.getBoolean(KEY_DARK_THEME_ENABLED, false)) ThemeMode.DARK else ThemeMode.LIGHT
            } else ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("theme_mode", mode.name).apply()
    }

    fun nightMode(context: Context): Int = when (getThemeMode(context)) {
        ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
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

    fun getProtectionIntervalMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PROTECTION_INTERVAL_MINUTES, DEFAULT_PROTECTION_INTERVAL_MINUTES)
            .let(::normalizeProtectionIntervalMinutes)
    }

    fun setProtectionIntervalMinutes(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PROTECTION_INTERVAL_MINUTES, normalizeProtectionIntervalMinutes(value))
            .apply()
    }

    internal fun normalizeProtectionIntervalMinutes(value: Int): Int {
        return value.coerceIn(PROTECTION_INTERVAL_STEP_MINUTES, MAX_PROTECTION_INTERVAL_MINUTES) /
            PROTECTION_INTERVAL_STEP_MINUTES * PROTECTION_INTERVAL_STEP_MINUTES
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
