package com.stanislavlyalin.stopenuresis.service

/** Первый положительный результат открывает фиксированный период подтверждения. */
class RustlingConfirmation(
    private val requiredEpisodes: Int,
    private val periodMillis: Long
) {
    init {
        require(requiredEpisodes > 0)
        require(periodMillis > 0)
    }

    data class Result(val newEpisode: Boolean, val alarm: Boolean)

    private var periodStartedAt: Long? = null
    private var previousWindowPositive = false
    @Volatile
    var episodeCount = 0
        private set

    val isConfirming: Boolean
        @Synchronized get() = periodStartedAt != null

    @Synchronized
    fun advance(now: Long) {
        val start = periodStartedAt ?: return
        if (now - start >= periodMillis) reset()
    }

    @Synchronized
    fun accept(positive: Boolean, now: Long, allowed: Boolean): Result {
        advance(now)
        if (!allowed) {
            previousWindowPositive = false
            return Result(false, false)
        }
        val newEpisode = positive && !previousWindowPositive
        previousWindowPositive = positive
        if (!newEpisode) return Result(false, false)

        if (periodStartedAt == null) periodStartedAt = now
        episodeCount++
        return Result(true, episodeCount >= requiredEpisodes)
    }

    @Synchronized
    fun reset() {
        periodStartedAt = null
        previousWindowPositive = false
        episodeCount = 0
    }
}
