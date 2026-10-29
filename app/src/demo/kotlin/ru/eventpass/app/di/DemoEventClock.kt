package ru.eventpass.app.di

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import ru.eventpass.data.fake.product.FakeFixtures

/** Event scenarios have a deterministic timeline, separate from auth UTC. */
class DemoEventClock : Clock() {
    @Volatile private var now = FakeFixtures.NOW
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    fun advance(duration: Duration) { now = now.plus(duration) }
    fun reset() { now = FakeFixtures.NOW }
}
