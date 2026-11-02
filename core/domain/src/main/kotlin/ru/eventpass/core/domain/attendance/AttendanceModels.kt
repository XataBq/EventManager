package ru.eventpass.core.domain.attendance

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.TicketStatus

enum class AttendanceError { AUTHENTICATION_REQUIRED, FORBIDDEN, NOT_FOUND, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, STALE_PAGE, INVALID_INPUT, USED }
data class AttendanceHeader(val eventId: EventId, val title: String, val startsAt: Instant, val zoneId: String,
    val venue: String, val lifecycle: EventLifecycle, val registrationOpen: Boolean, val finished: Boolean)
data class AttendanceSummary(val registered: Int, val checkedIn: Int, val notCheckedIn: Int, val remaining: Int?) {
    val percent: Int get() = if (registered == 0) 0 else (checkedIn.toLong() * 100 / registered).toInt()
}
data class AttendanceDashboard(val header: AttendanceHeader, val summary: AttendanceSummary, val revision: String, val updatedAt: Instant)
sealed interface DashboardResult {
    data class Ready(val dashboard: AttendanceDashboard) : DashboardResult
    data class Failed(val error: AttendanceError) : DashboardResult
}
/** Projection contains no QR/manual credentials, answers or phone. */
data class Attendee(val registrationId: String, val ticketId: TicketId, val name: String, val email: String,
    val displayNumber: String, val status: TicketStatus, val registeredAt: Instant, val checkedAt: Instant?, val checkerId: UserId?) {
    override fun toString() = "Attendee(registrationId=$registrationId, status=$status, contact=[redacted])"
}
/** Demo revision-bound offset, not an agreed remote cursor/security protocol. */
data class AttendanceCursor(val eventId: EventId, val revision: String, val query: String, val offset: Int)
sealed interface AttendeesResult {
    data class Page(val revision: String, val rows: List<Attendee>, val totalMatches: Int, val next: AttendanceCursor?) : AttendeesResult
    data class Failed(val error: AttendanceError) : AttendeesResult
}
sealed interface OwnerCancelResult {
    data object Cancelled : OwnerCancelResult
    data class Failed(val error: AttendanceError) : OwnerCancelResult
}
interface AttendanceRepository {
    fun observeDashboard(eventId: EventId): Flow<DashboardResult>
    suspend fun refreshDashboard(eventId: EventId): DashboardResult
    suspend fun attendees(eventId: EventId, query: String, cursor: AttendanceCursor? = null): AttendeesResult
    suspend fun cancelRegistration(eventId: EventId, registrationId: String): OwnerCancelResult
}
