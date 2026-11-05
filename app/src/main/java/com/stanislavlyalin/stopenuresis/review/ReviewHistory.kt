package com.stanislavlyalin.stopenuresis.review

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal object ReviewPolicy {
    const val MIN_ACCURACY = 0.90
    const val REQUIRED_ALARMS = 5
    const val REQUIRED_NIGHTS = 3
    const val DAY_MS = 86_400_000L
    const val QUIET_DELAY_MS = 20_000L
    const val AFTER_ALARM_MS = 2 * 60 * 60 * 1000L
    const val RETRY_MS = 30 * DAY_MS
    const val MAX_ATTEMPTS = 2

    fun date(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(at)

    fun night(at: Long): String? {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour in 8 until 21) return null
        if (hour < 8) calendar.add(Calendar.DAY_OF_MONTH, -1)
        return date(calendar.timeInMillis)
    }

    fun morningAfter(at: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        if (calendar.get(Calendar.HOUR_OF_DAY) >= 21) calendar.add(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 8)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun daytime(at: Long): Boolean = Calendar.getInstance().apply { timeInMillis = at }
        .get(Calendar.HOUR_OF_DAY) in 8 until 20
}

/** Все изменения истории выполняются в главном потоке сервиса или Activity. */
internal class ReviewHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("review_history", Context.MODE_PRIVATE)

    fun alarmStarted(at: Long, accuracy: Double): Boolean {
        return prefs.edit().putLong("last_alarm_at", at).putBoolean("alarm_eligible", accuracy >= ReviewPolicy.MIN_ACCURACY)
            .putBoolean("alarm_counted", false).commit()
    }

    fun alarmDismissed() {
        val at = prefs.getLong("last_alarm_at", 0)
        val night = ReviewPolicy.night(at) ?: return
        if (at <= 0 || prefs.getBoolean("alarm_counted", false) ||
            !prefs.getBoolean("alarm_eligible", false)) return
        val count = (prefs.getInt("alarm_count", 0) + 1).coerceAtMost(ReviewPolicy.REQUIRED_ALARMS)
        val nights = prefs.getStringSet("nights", emptySet()).orEmpty().toMutableSet()
        if (nights.size < ReviewPolicy.REQUIRED_NIGHTS) nights.add(night)
        val edit = prefs.edit().putBoolean("alarm_counted", true).putInt("alarm_count", count)
            .putStringSet("nights", nights)
        if (count >= ReviewPolicy.REQUIRED_ALARMS && nights.size >= ReviewPolicy.REQUIRED_NIGHTS &&
            prefs.getLong("eligible_at", 0) == 0L) edit.putLong("eligible_at", ReviewPolicy.morningAfter(at))
        edit.commit()
    }

    fun allows(now: Long, accuracy: Double?): Boolean {
        val eligible = prefs.getLong("eligible_at", 0)
        val lastAlarm = prefs.getLong("last_alarm_at", 0)
        val attempts = prefs.getInt("attempt_count", 0)
        val lastAttempt = prefs.getLong("last_attempt_at", 0)
        return accuracy != null && accuracy >= ReviewPolicy.MIN_ACCURACY &&
            !prefs.getBoolean("review_exists", false) && attempts < ReviewPolicy.MAX_ATTEMPTS &&
            prefs.getInt("alarm_count", 0) >= ReviewPolicy.REQUIRED_ALARMS &&
            prefs.getStringSet("nights", emptySet()).orEmpty().size >= ReviewPolicy.REQUIRED_NIGHTS &&
            eligible > 0 && now >= eligible && lastAlarm > 0 && now >= lastAlarm &&
            now - lastAlarm >= ReviewPolicy.AFTER_ALARM_MS && ReviewPolicy.daytime(now) &&
            (attempts == 0 || (now >= lastAttempt && now - lastAttempt >= ReviewPolicy.RETRY_MS))
    }

    fun reservePreparation(now: Long): Boolean {
        val today = ReviewPolicy.date(now)
        if (prefs.getString("preparation_date", null) == today) return false
        return prefs.edit().putString("preparation_date", today).commit()
    }

    fun reserveAttempt(now: Long): Boolean = prefs.edit().putLong("last_attempt_at", now)
        .putInt("attempt_count", prefs.getInt("attempt_count", 0) + 1).commit()

    fun reviewExists() { prefs.edit().putBoolean("review_exists", true).commit() }
}
