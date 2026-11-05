package com.stanislavlyalin.stopenuresis.review

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.stanislavlyalin.stopenuresis.service.DataCollectionRecordingService
import com.stanislavlyalin.stopenuresis.service.UsageMonitoringService
import com.stanislavlyalin.stopenuresis.ui.TrainingFragment

/** Только MainActivity может инициировать форму; сервис лишь накапливает опыт. */
internal class ReviewPrompt(
    private val activity: AppCompatActivity,
    private val suitableScreen: () -> Boolean,
    private val accuracy: () -> Double?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val history = ReviewHistory(activity)
    private val sdk = RuStoreReviewAdapter(activity.applicationContext)
    private var quietSince = 0L
    private var touching = false
    private var disposed = false
    private val check = Runnable { tryReview() }

    companion object {
        // Подготовка может пережить поворот Activity: одновременно допускается один запрос.
        private var inFlight = false
    }

    private fun hostReady(): Boolean = !disposed && !touching && !activity.isFinishing &&
        !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        activity.hasWindowFocus() && suitableScreen() &&
        activity.getSystemService(PowerManager::class.java).isInteractive &&
        !activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        UsageMonitoringService.stateSnapshot == UsageMonitoringService.STATE_IDLE &&
        !DataCollectionRecordingService.isRecordingNow && !TrainingFragment.isTrainingNow

    fun schedule() {
        handler.removeCallbacks(check)
        quietSince = SystemClock.elapsedRealtime()
        if (hostReady()) handler.postDelayed(check, ReviewPolicy.QUIET_DELAY_MS)
    }

    fun touch(active: Boolean) { touching = active; if (active) cancel() else schedule() }
    fun pause() { touching = false; cancel() }
    fun cancel() { handler.removeCallbacks(check) }
    fun dispose() { disposed = true; cancel() }

    private fun tryReview() {
        if (inFlight || !hostReady()) return
        val now = System.currentTimeMillis()
        if (!history.allows(now, accuracy()) || !history.reservePreparation(now)) return
        inFlight = true
        val preparedAt = SystemClock.elapsedRealtime()
        fun failed(error: Throwable?) {
            if (error != null && sdk.reviewExists(error)) history.reviewExists()
            inFlight = false
        }
        try {
            sdk.prepare({ info ->
                try {
                    fun ready(): Boolean = hostReady() &&
                        SystemClock.elapsedRealtime() - quietSince >= ReviewPolicy.QUIET_DELAY_MS && ReviewPolicy.daytime(System.currentTimeMillis()) &&
                        SystemClock.elapsedRealtime() - preparedAt in 0..240_000L
                    if (!ready() || !history.allows(System.currentTimeMillis(), accuracy())) {
                        inFlight = false
                    } else if (history.reserveAttempt(System.currentTimeMillis(), ::ready)) {
                        sdk.launch(info, ::failed)
                    } else inFlight = false
                } catch (error: Exception) { failed(error) }
            }, ::failed)
        } catch (error: Exception) { failed(error) }
    }
}
