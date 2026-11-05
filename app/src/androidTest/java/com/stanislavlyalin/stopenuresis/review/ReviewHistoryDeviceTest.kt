package com.stanislavlyalin.stopenuresis.review

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReviewHistoryDeviceTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("device_test_$name", mode)
    }
    private val prefs get() = context.getSharedPreferences("review_history", Context.MODE_PRIVATE)
    private val history get() = ReviewHistory(context)
    private lateinit var previousZone: TimeZone
    private fun at(text: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(text)!!.time

    @Before fun setup() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        prefs.edit().clear().commit()
    }
    @After fun cleanup() { prefs.edit().clear().commit(); TimeZone.setDefault(previousZone) }

    private fun accumulate() {
        for (date in listOf("2026-01-01 22:00", "2026-01-02 01:00", "2026-01-02 22:00", "2026-01-03 22:00")) {
            assertTrue(history.alarmStarted(at(date), .90)); history.alarmDismissed(); history.alarmDismissed()
        }
    }

    @Test fun thresholdNightsAndDaytimeAreExact() {
        assertNull(ReviewPolicy.night(at("2026-01-01 20:59")))
        assertEquals("2026-01-01", ReviewPolicy.night(at("2026-01-01 21:00")))
        assertEquals("2026-01-01", ReviewPolicy.night(at("2026-01-02 07:59")))
        assertNull(ReviewPolicy.night(at("2026-01-02 08:00")))
        accumulate()
        assertEquals(4, prefs.getInt("alarm_count", 0))
        history.alarmStarted(at("2026-01-04 06:00"), .8999); history.alarmDismissed()
        assertFalse(history.allows(at("2026-01-04 08:00"), .90))
        history.alarmStarted(at("2026-01-04 06:00"), .90); history.alarmDismissed()
        assertTrue(ReviewHistory(context).allows(at("2026-01-04 08:00"), .90))
        assertFalse(history.allows(at("2026-01-04 08:00"), .8999))
        assertFalse(history.allows(at("2026-01-04 07:59"), .90))
        assertTrue(history.allows(at("2026-01-04 19:59"), .90))
        assertFalse(history.allows(at("2026-01-04 20:00"), .90))
        history.alarmStarted(at("2026-01-04 12:00"), .50); history.alarmDismissed()
        assertEquals(5, prefs.getInt("alarm_count", 0))
        assertFalse(history.allows(at("2026-01-04 13:59"), .90))
        assertTrue(history.allows(at("2026-01-04 14:00"), .90))
    }

    @Test fun attemptsPersistAndUnusedReservationIsReturned() {
        accumulate(); history.alarmStarted(at("2026-01-04 06:00"), .90); history.alarmDismissed()
        val now = at("2026-01-04 08:00")
        assertTrue(history.reservePreparation(now)); assertFalse(history.reservePreparation(now))
        assertFalse(history.reserveAttempt(now) { false })
        assertTrue(history.allows(now, .90))
        assertTrue(history.reserveAttempt(now) { true })
        assertFalse(ReviewHistory(context).allows(now + 29 * ReviewPolicy.DAY_MS, .90))
        assertTrue(history.allows(now + 30 * ReviewPolicy.DAY_MS, .90))
        assertTrue(history.reserveAttempt(now + 30 * ReviewPolicy.DAY_MS) { true })
        assertFalse(history.allows(now + 60 * ReviewPolicy.DAY_MS, .90))
        prefs.edit().putInt("attempt_count", 0).commit()
        history.reviewExists()
        assertFalse(ReviewHistory(context).allows(now + 60 * ReviewPolicy.DAY_MS, .90))
    }
    @Test fun missingRuStoreReturnsErrorWithoutLaunchingUi() {
        org.junit.Assume.assumeTrue(runCatching { context.packageManager.getPackageInfo("ru.vk.store", 0) }.isFailure)
        val completed = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        var prepared = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            RuStoreReviewAdapter(context).prepare({ prepared = true; completed.countDown() },
                { error.set(it); completed.countDown() })
        }
        assertTrue(completed.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(prepared)
        assertNotNull(error.get())
    }
}
