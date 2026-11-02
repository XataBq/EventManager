package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

internal class FakeAttendanceOperations(private val state: MutableStateFlow<FakeProductState>, private val lock: Mutex,
    private val clock: Clock, private val authClock: Clock, private val storage: FakeAuthStorage, private val auth: FakeAuthRepository) : AttendanceRepository {
    private fun guard(s: FakeProductState, id: EventId): AttendanceError? {
        if (!s.auth.healthy) return AttendanceError.STORAGE_UNAVAILABLE
        val account = s.activeAccount(authClock.instant())?.takeIf { it.emailVerified } ?: return AttendanceError.AUTHENTICATION_REQUIRED
        val event = s.events[id] ?: return AttendanceError.NOT_FOUND
        return if (EventAccessPolicy.resolve(event, account, false, s.grants, event.registrationAvailability(clock.instant(), 0)).canViewAttendees)
            null else AttendanceError.FORBIDDEN
    }
    private fun rows(s: FakeProductState, id: EventId): List<Attendee> {
        val registrations = s.registrations.filter { it.eventId == id }.associateBy { it.id }
        val records = s.checkIns.filter { it.eventId == id }.associateBy { it.ticketId }
        return s.tickets.filter { it.snapshot.eventId == id }.mapNotNull { t ->
            registrations[t.registrationId]?.let { r ->
                val first = records[t.snapshot.ticketId]
                Attendee(r.id, t.snapshot.ticketId, r.attendeeName, r.attendeeEmail, t.snapshot.displayNumber, t.status,
                    r.consentAcceptedAt, first?.checkedAt, first?.checkerId)
            }
        }.sortedWith(compareByDescending<Attendee> { it.registeredAt }.thenBy { it.registrationId }.thenBy { it.ticketId.value })
    }
    private fun revision(s: FakeProductState, id: EventId, rows: List<Attendee>): String {
        // Serialize all projection fields explicitly; Attendee.toString deliberately redacts PII.
        val fields = buildList {
            add(id.value); add(s.auth.generation.toString()); add(s.events.getValue(id).toString())
            rows.forEach { addAll(listOf(it.registrationId, it.ticketId.value, it.name, it.email, it.displayNumber,
                it.status.name, it.registeredAt.toString(), it.checkedAt.toString(), it.checkerId?.value.orEmpty())) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { val bytes = it.toByteArray(Charsets.UTF_8); digest.update("${bytes.size}:".toByteArray()); digest.update(bytes) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun dashboard(s: FakeProductState, id: EventId): DashboardResult {
        guard(s, id)?.let { return DashboardResult.Failed(it) }
        s.attendanceReadErrors[id]?.let { return DashboardResult.Failed(it) }
        val e = s.events.getValue(id); val rows = rows(s, id)
        val active = rows.count { it.status == TicketStatus.ACTIVE }; val used = rows.count { it.status == TicketStatus.USED }
        val now = clock.instant()
        return DashboardResult.Ready(AttendanceDashboard(AttendanceHeader(id, e.title, e.startsAt, e.zoneId, e.venue,
            e.lifecycle, e.registrationOpen, e.lifecycle == EventLifecycle.FINISHED || e.endsAt?.let { now >= it } == true),
            AttendanceSummary(active + used, used, active, e.capacity?.let { (it - active - used).coerceAtLeast(0) }), revision(s, id, rows), now))
    }
    override fun observeDashboard(eventId: EventId): Flow<DashboardResult> = flow { auth.initialize(); emitAll(state) }
        .map { dashboard(it, eventId) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override suspend fun refreshDashboard(eventId: EventId): DashboardResult {
        auth.initialize()
        return withContext(Dispatchers.IO) { lock.withLock {
            val old = state.value; guard(old, eventId)?.let { return@withLock DashboardResult.Failed(it) }
            val next = old.copy(attendanceReadErrors = old.nextAttendanceReadError?.let { old.attendanceReadErrors + (eventId to it) }
                ?: (old.attendanceReadErrors - eventId), nextAttendanceReadError = null)
            state.value = next; dashboard(next, eventId)
        } }
    }
    override suspend fun attendees(eventId: EventId, query: String, cursor: AttendanceCursor?): AttendeesResult {
        auth.initialize()
        return withContext(Dispatchers.Default) {
            val s = state.value; guard(s, eventId)?.let { return@withContext AttendeesResult.Failed(it) }
            s.attendanceReadErrors[eventId]?.let { return@withContext AttendeesResult.Failed(it) }
            if (query.length > 254) return@withContext AttendeesResult.Failed(AttendanceError.INVALID_INPUT)
            val normalized = query.trim().lowercase(Locale.ROOT); val all = rows(s, eventId); val rev = revision(s, eventId, all)
            if (cursor != null && (cursor.eventId != eventId || cursor.revision != rev || cursor.query != normalized))
                return@withContext AttendeesResult.Failed(AttendanceError.STALE_PAGE)
            val filtered = all.filter { it.name.lowercase(Locale.ROOT).contains(normalized) || it.displayNumber.lowercase(Locale.ROOT).contains(normalized) }
            val offset = cursor?.offset ?: 0
            if (offset < 0 || offset > filtered.size) return@withContext AttendeesResult.Failed(AttendanceError.INVALID_INPUT)
            val page = filtered.drop(offset).take(20); val next = offset + page.size
            // No suspension between this access check and the returned snapshot; VM also guards its owner generation.
            guard(state.value, eventId)?.let { return@withContext AttendeesResult.Failed(it) }
            if (s.auth.generation != state.value.auth.generation) return@withContext AttendeesResult.Failed(AttendanceError.SESSION_CHANGED)
            AttendeesResult.Page(rev, page, filtered.size, if (next < filtered.size) AttendanceCursor(eventId, rev, normalized, next) else null)
        }
    }
    override suspend fun cancelRegistration(eventId: EventId, registrationId: String): OwnerCancelResult {
        auth.initialize(); val generation = state.value.auth.generation
        return withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive(); val s = state.value
            if (generation != s.auth.generation) return@withLock OwnerCancelResult.Failed(AttendanceError.SESSION_CHANGED)
            guard(s, eventId)?.let { return@withLock OwnerCancelResult.Failed(it) }
            val registration = s.registrations.singleOrNull { it.id == registrationId && it.eventId == eventId }
                ?: return@withLock OwnerCancelResult.Failed(AttendanceError.NOT_FOUND)
            val ticket = s.tickets.singleOrNull { it.registrationId == registration.id && it.snapshot.eventId == eventId }
                ?: return@withLock OwnerCancelResult.Failed(AttendanceError.NOT_FOUND)
            if (ticket.status == TicketStatus.USED) return@withLock OwnerCancelResult.Failed(AttendanceError.USED)
            val fault = s.nextTicketError
            val (next, result) = if (fault != null) s.copy(nextTicketError = null) to CancelTicketOutcome.Failed(fault) else s.cancelTicket(ticket)
            if (next != s) {
                try { storage.write(ProductSnapshotCodec.encode(next)) } catch (_: IOException) { return@withLock OwnerCancelResult.Failed(AttendanceError.STORAGE_UNAVAILABLE) }
                state.value = next
            }
            when (result) {
                is CancelTicketOutcome.Cancelled -> OwnerCancelResult.Cancelled
                is CancelTicketOutcome.Failed -> OwnerCancelResult.Failed(AttendanceError.valueOf(result.error.name))
            }
        } }
    }
}
