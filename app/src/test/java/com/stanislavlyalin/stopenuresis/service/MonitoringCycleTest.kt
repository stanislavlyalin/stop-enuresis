package com.stanislavlyalin.stopenuresis.service

import org.junit.Assert.*
import org.junit.Test

class MonitoringCycleTest {
    @Test
    fun fullNightCycleWaitsAfterStartAndAfterDismiss() {
        val cycle = MonitoringCycle()
        assertTrue(cycle.start(100, 60))
        assertEquals(MonitoringCycle.PAUSED, cycle.state)
        assertFalse(cycle.alarm())
        assertFalse(cycle.resume(159))
        assertTrue(cycle.resume(160))
        assertEquals(MonitoringCycle.MONITORING, cycle.state)
        assertTrue(cycle.alarm())
        assertTrue(cycle.dismiss(200, 60))
        assertEquals(260L, cycle.resumeAt)
        assertFalse(cycle.resume(259))
        assertTrue(cycle.resume(260))
        assertTrue(cycle.alarm())
    }

    @Test
    fun repeatedCommandsDoNotExtendPauseOrDismissAnotherState() {
        val cycle = MonitoringCycle()
        assertFalse(cycle.dismiss(0, 60))
        cycle.start(10, 60)
        assertFalse(cycle.start(20, 120))
        assertFalse(cycle.dismiss(30, 120))
        assertEquals(70L, cycle.resumeAt)
        cycle.resume(70)
        cycle.alarm()
        cycle.dismiss(80, 120)
        assertFalse(cycle.dismiss(90, 60))
        assertEquals(200L, cycle.resumeAt)
    }

    @Test
    fun stopCancelsPendingResumeAndAllowsANewSession() {
        val cycle = MonitoringCycle()
        cycle.start(0, 60)
        cycle.stop()
        assertEquals(MonitoringCycle.IDLE, cycle.state)
        assertEquals(0L, cycle.resumeAt)
        assertFalse(cycle.resume(100))
        assertTrue(cycle.start(200, 120))
        assertEquals(320L, cycle.resumeAt)
    }
}
