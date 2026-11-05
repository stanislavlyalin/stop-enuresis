package com.stanislavlyalin.stopenuresis.service

/** Переходы ночного сеанса. Время передаётся по монотонным часам. */
internal class MonitoringCycle {
    var state = IDLE
        private set
    var resumeAt = 0L
        private set

    fun start(now: Long, intervalMillis: Long): Boolean {
        if (state != IDLE) return false
        pause(now, intervalMillis)
        return true
    }

    fun dismiss(now: Long, intervalMillis: Long): Boolean {
        if (state != ALARMING) return false
        pause(now, intervalMillis)
        return true
    }

    fun resume(now: Long): Boolean {
        if (state != PAUSED || now < resumeAt) return false
        state = MONITORING
        resumeAt = 0L
        return true
    }

    fun alarm(): Boolean {
        if (state != MONITORING) return false
        state = ALARMING
        return true
    }

    fun stop() {
        state = IDLE
        resumeAt = 0L
    }

    private fun pause(now: Long, intervalMillis: Long) {
        state = PAUSED
        resumeAt = now + intervalMillis
    }

    companion object {
        const val IDLE = "idle"
        const val PAUSED = "paused"
        const val MONITORING = "monitoring"
        const val ALARMING = "alarming"
    }
}
