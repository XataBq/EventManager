package ru.eventpass.core.domain.documents

import java.time.Instant
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

enum class DocumentError { AUTHENTICATION_REQUIRED, SESSION_CHANGED, FORBIDDEN, NOT_FOUND, NOT_CURRENT,
    NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, PROVIDER_UNAVAILABLE, WRITE_FAILED }
sealed interface DocumentRequest {
    data class TicketPdf(val ticketId: TicketId) : DocumentRequest
    data class AttendanceXlsx(val eventId: EventId) : DocumentRequest
}
val DocumentRequest.mimeType: String get() = when (this) {
    is DocumentRequest.TicketPdf -> "application/pdf"
    is DocumentRequest.AttendanceXlsx -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}
val DocumentRequest.fileName: String get() = when (this) {
    is DocumentRequest.TicketPdf -> "eventpass-ticket.pdf"
    is DocumentRequest.AttendanceXlsx -> "eventpass-attendees.xlsx"
}
data class DocumentStamp(val accountId: UserId, val generation: Long)
data class ExportAttendee(val registration: Registration, val displayNumber: String, val status: TicketStatus,
    val checkedAt: Instant?, val checkerId: UserId?) {
    override fun toString() = "ExportAttendee([redacted])"
}
sealed interface DocumentSnapshot {
    val stamp: DocumentStamp
    val request: DocumentRequest
    data class Ticket(override val stamp: DocumentStamp, override val request: DocumentRequest.TicketPdf,
        val ticket: GuestTicketSnapshot) : DocumentSnapshot {
        override fun toString() = "TicketDocument([redacted])"
    }
    data class Attendance(override val stamp: DocumentStamp, override val request: DocumentRequest.AttendanceXlsx,
        val event: Event, val snapshotAt: Instant, val rows: List<ExportAttendee>) : DocumentSnapshot {
        override fun toString() = "AttendanceDocument([redacted])"
    }
}
sealed interface DocumentResult {
    data class Ready(val snapshot: DocumentSnapshot) : DocumentResult
    data class Failed(val error: DocumentError) : DocumentResult
}
sealed interface ResendResult {
    data class Queued(val retryAt: Instant) : ResendResult
    data class Cooldown(val retryAt: Instant) : ResendResult
    data class Failed(val error: DocumentError) : ResendResult
}
interface DocumentsRepository {
    suspend fun prepare(request: DocumentRequest): DocumentResult
    suspend fun validate(snapshot: DocumentSnapshot): DocumentError?
    suspend fun resendTicket(ticketId: TicketId): ResendResult
}
/** Destination is an opaque platform locator, never an unrestricted filesystem path. */
interface DocumentFiles {
    suspend fun save(snapshot: DocumentSnapshot, destination: String, authorize: suspend () -> DocumentError?): DocumentError?
    suspend fun cleanup()
}
