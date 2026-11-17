package ru.eventpass.core.domain.documents

import java.time.Instant
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

enum class DocumentError { FEATURE_UNAVAILABLE, AUTHENTICATION_REQUIRED, SESSION_CHANGED, FORBIDDEN, NOT_FOUND, NOT_CURRENT,
    NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, PROVIDER_UNAVAILABLE, WRITE_FAILED, PROTOCOL, SERVER_UNAVAILABLE}
sealed interface DocumentRequest {
    data class TicketPdf(val ticketId: TicketId, val guestAccess: String? = null) : DocumentRequest {
        override fun toString() = "TicketPdf(ticketId=$ticketId, access=[redacted])"
    }
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
data class DocumentStamp(val accountId: UserId?, val generation: Long)
data class ExportAttendee(val registration: Registration, val displayNumber: String, val status: TicketStatus,
    val checkedAt: Instant?, val checkerId: UserId?) {
    override fun toString() = "ExportAttendee([redacted])"
}
sealed interface DocumentSnapshot {
    val stamp: DocumentStamp
    val request: DocumentRequest
    /** Transient download source; never serialized or retained as an offline product cache. */
    class Remote(override val stamp: DocumentStamp, override val request: DocumentRequest,
        val source: DocumentSource,val current:()->Boolean={true}) : DocumentSnapshot {
        override fun toString() = "RemoteDocument([redacted])"
    }
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
interface DocumentsRepository {
    suspend fun prepare(request: DocumentRequest): DocumentResult
    suspend fun validate(snapshot: DocumentSnapshot): DocumentError?
}
/** Destination is an opaque platform locator, never an unrestricted filesystem path. */
interface DocumentFiles {
    suspend fun save(snapshot: DocumentSnapshot, destination: String, authorize: suspend () -> DocumentError?): DocumentError?
    suspend fun cleanup()
    suspend fun purgePrivate(accountId:UserId) {cleanup()}
}

fun interface DocumentSource { suspend fun writeTo(output: java.io.OutputStream) }

class DocumentFailure(val error: DocumentError) : java.io.IOException("Document operation failed")
