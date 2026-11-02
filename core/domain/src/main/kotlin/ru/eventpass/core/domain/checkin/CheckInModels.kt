package ru.eventpass.core.domain.checkin

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.*

@JvmInline value class QrToken(val value: String) { override fun toString() = "QrToken([redacted])" }
@JvmInline value class ManualCode(val value: String) { override fun toString() = "ManualCode([redacted])" }
sealed interface TicketCredential {
    data class Qr(val token: QrToken) : TicketCredential
    data class Manual(val code: ManualCode) : TicketCredential
}

/** Temporary demo format, not a backend credential protocol. Derived from the opaque secret, never ticket number. */
object ManualCodePolicy {
    private const val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    fun fromQr(payload: String): ManualCode {
        val bytes = MessageDigest.getInstance("SHA-256").digest(("eventpass-demo-manual-v1\u0000" + payload).toByteArray(Charsets.UTF_8))
        return ManualCode(buildString { repeat(12) { append(alphabet[bytes[it].toInt() and 31]) } })
    }
    fun parse(raw: String): ManualCode? {
        if (raw.length > 64) return null
        val normalized = raw.trim().replace("-", "").replace(" ", "").uppercase(Locale.ROOT)
        return normalized.takeIf { it.length == 12 && it.all(alphabet::contains) }?.let(::ManualCode)
    }
}

enum class CheckInError { AUTHENTICATION_REQUIRED, FORBIDDEN, NOT_FOUND, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, INVALID_INPUT }
enum class InvalidTicketReason { UNKNOWN, WRONG_EVENT, CANCELLED, EVENT_INACTIVE }

data class CheckInRecord(val id: String, val eventId: EventId, val ticketId: TicketId, val checkerId: UserId,
    val checkedAt: Instant, val attendeeName: String, val displayNumber: String) {
    override fun toString() = "CheckInRecord(id=$id, eventId=$eventId, attendee=[redacted])"
}
sealed interface CheckInOutcome {
    data class Admitted(val record: CheckInRecord) : CheckInOutcome
    /** Null only for legacy/debug Used without historical checker/time; never invent a first check-in. */
    data class AlreadyUsed(val first: CheckInRecord?) : CheckInOutcome
    data class Invalid(val reason: InvalidTicketReason) : CheckInOutcome
    data class Failed(val error: CheckInError) : CheckInOutcome
}

data class ScannerScope(val actorId: UserId, val generation: Long)
sealed interface ScannerAccess {
    data class Allowed(val eventId: EventId, val title: String, val scope: ScannerScope) : ScannerAccess
    data class Denied(val error: CheckInError) : ScannerAccess
}
interface CheckInRepository {
    fun observeAccess(eventId: EventId): Flow<ScannerAccess>
    suspend fun refreshAccess(eventId: EventId)
    suspend fun checkIn(eventId: EventId, credential: TicketCredential): CheckInOutcome
}

data class Volunteer(val grant: ScanGrant, val email: String, val checkedInCount: Int = 0)
enum class VolunteerError { AUTHENTICATION_REQUIRED, FORBIDDEN, NOT_FOUND, ACCOUNT_NOT_FOUND, INVALID_TARGET, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE }
sealed interface VolunteersResult {
    data class Ready(val volunteers: List<Volunteer>) : VolunteersResult
    data class Failed(val error: VolunteerError) : VolunteersResult
}
sealed interface VolunteerOutcome {
    data class Updated(val grant: ScanGrant) : VolunteerOutcome
    data class Failed(val error: VolunteerError) : VolunteerOutcome
}
interface VolunteerRepository {
    fun observeVolunteers(eventId: EventId): Flow<VolunteersResult>
    suspend fun grant(eventId: EventId, emailOrId: String): VolunteerOutcome
    suspend fun revoke(eventId: EventId, userId: UserId): VolunteerOutcome
}
