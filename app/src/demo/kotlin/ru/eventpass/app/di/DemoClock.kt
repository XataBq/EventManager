package ru.eventpass.app.di

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Only auth time moves; event fixture time is independent. In-memory demo control. */
class DemoClock : Clock() {
    @Volatile private var offset: Duration = Duration.ZERO
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    override fun instant(): Instant = Instant.now().plus(offset)
    fun advance(duration: Duration) { offset = offset.plus(duration) }
    fun reset() { offset = Duration.ZERO }
}
