package ru.eventpass.core.domain.attendance

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.TicketStatus
import ru.eventpass.core.domain.registration.Answer
import ru.eventpass.core.domain.checkin.VolunteerError

enum class AttendanceError { FEATURE_UNAVAILABLE, AUTHENTICATION_REQUIRED, FORBIDDEN, NOT_FOUND, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, STALE_PAGE, INVALID_INPUT, USED, PROTOCOL, SERVER_UNAVAILABLE, RATE_LIMITED }
data class AttendanceHeader(val eventId: EventId, val title: String, val startsAt: Instant, val zoneId: String,
    val venue: String, val lifecycle: EventLifecycle, val registrationOpen: Boolean, val finished: Boolean,
    val statusAuthoritative:Boolean=false)
data class AttendanceSummary(val registered: Int, val checkedIn: Int, val notCheckedIn: Int, val remaining: Int?,val capacity:Int?=null) {
    val percent: Int get() = if (registered == 0) 0 else (checkedIn.toLong() * 100 / registered).toInt()
}
data class AttendanceDashboard(val header: AttendanceHeader, val summary: AttendanceSummary, val revision: String, val updatedAt: Instant,
    val volunteerCount:Int?=null,val volunteerError:VolunteerError?=null) {
    /** Local receipt time, never an invented server updatedAt. */
    val fetchedAt:Instant get()=updatedAt
}
sealed interface DashboardResult {
    data class Ready(val dashboard: AttendanceDashboard,val firstPage:AttendeesResult.Page?=null) : DashboardResult
    data class Failed(val error: AttendanceError) : DashboardResult
}
/** History projection: no QR/scan credential; absent legacy registration/checker data stays null. */
data class Attendee(val registrationId: String?, val ticketId: TicketId, val name: String, val email: String,
    val displayNumber: String, val status: TicketStatus, val registeredAt: Instant?, val checkedAt: Instant?, val checkerId: UserId?,
    val answers:Map<String,Answer> = emptyMap()) {
    override fun toString() = "Attendee(status=$status, contact/answers=[redacted])"
}
/** Demo revision-bound offset, not an agreed remote cursor/security protocol. */
data class AttendanceCursor(val eventId: EventId, val revision: String, val query: String, val offset: Int=0,
    val serverPage:Int?=null,val pageSize:Int?=null)
sealed interface AttendeesResult {
    data class Page(val revision: String, val rows: List<Attendee>, val totalMatches: Int, val next: AttendanceCursor?,
        val query:String?=null,val serverPage:Int?=null,val pageSize:Int?=null) : AttendeesResult
    data class Failed(val error: AttendanceError) : AttendeesResult
}
sealed interface OwnerCancelResult {
    data object Cancelled : OwnerCancelResult
    data class Failed(val error: AttendanceError) : OwnerCancelResult
}
interface AttendanceRepository {
    fun observeDashboard(eventId: EventId): Flow<DashboardResult>
    fun observeDashboard(eventId:EventId,query:String):Flow<DashboardResult> = observeDashboard(eventId)
    suspend fun refreshDashboard(eventId: EventId): DashboardResult
    suspend fun refreshDashboard(eventId:EventId,query:String):DashboardResult = refreshDashboard(eventId)
    suspend fun attendees(eventId: EventId, query: String, cursor: AttendanceCursor? = null): AttendeesResult
    suspend fun cancelRegistration(eventId: EventId, registrationId: String): OwnerCancelResult
    suspend fun cancelTicket(eventId:EventId,ticketId:TicketId):OwnerCancelResult = OwnerCancelResult.Failed(AttendanceError.FEATURE_UNAVAILABLE)
}
