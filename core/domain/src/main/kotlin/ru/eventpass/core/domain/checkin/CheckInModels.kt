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
        val server = raw.trim().uppercase(Locale.ROOT)
        if(server.matches(Regex("EVT-[A-Z0-9]{6}"))) return ManualCode(server)
        val normalized = raw.trim().replace("-", "").replace(" ", "").uppercase(Locale.ROOT)
        return normalized.takeIf { it.length == 12 && it.all(alphabet::contains) }?.let(::ManualCode)
    }
}

object ServerTicketCodePolicy {
    fun parse(raw:String):ManualCode? = raw.takeIf { it.length<=64 }?.trim()?.uppercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("EVT-[A-Z0-9]{6}")) }?.let(::ManualCode)
}

enum class CheckInError { FEATURE_UNAVAILABLE, AUTHENTICATION_REQUIRED, FORBIDDEN, NOT_FOUND, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, INVALID_INPUT, PROTOCOL, SERVER_UNAVAILABLE, EVENT_FINISHED, RATE_LIMITED, PENDING_RETRY, RECONCILIATION_REQUIRED }
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
    /** Sparse receipt: missing ticket/checker IDs never become a synthetic history record. */
    data class Confirmed(val decision:ScanDecision,val receipt:ScanReceipt,val replayed:Boolean=false) : CheckInOutcome
    data class Failed(val error: CheckInError,val retryAt:Instant?=null,val uncertain:Boolean=false) : CheckInOutcome
}

enum class ScanDecision { ALLOWED, ALREADY_USED, INVALID }
enum class ScannerRole { ORGANIZER, VOLUNTEER }
data class ScanReceipt(val scanId:String?=null,val reason:String?=null,val ticketCode:String?=null,
    val holderName:String?=null,val category:String?=null,val checkedInAt:Instant?=null,val checkedInBy:String?=null) {
    override fun toString()="ScanReceipt([redacted])"
}
data class ScanLogEntry(val id:String,val scannedAt:Instant,val decision:ScanDecision,
    val holderName:String?,val ticketCode:String?,val note:String) {
    override fun toString()="ScanLogEntry([redacted])"
}
data class ScannerInfo(val event:MyEventSummary,val role:ScannerRole,val checkedByMe:Int,val recent:List<ScanLogEntry>)
/** Immutable entry-owned command; no credential in Room, DataStore or SavedState. */
class ScanCommand(val credential:TicketCredential,val id:String=java.util.UUID.randomUUID().toString(),val scope:ScannerScope?=null) {
    private val attempted=java.util.concurrent.atomic.AtomicReference<Instant?>(null)
    val firstAttemptAt:Instant? get()=attempted.get()
    fun markAttempt(at:Instant):Instant {attempted.compareAndSet(null,at);return requireNotNull(attempted.get())}
    override fun toString()="ScanCommand([redacted])"
}

data class ScannerScope(val actorId: UserId, val generation: Long)
sealed interface ScannerAccess {
    data class Allowed(val eventId: EventId, val title: String, val scope: ScannerScope,
        val info:ScannerInfo?=null,val readError:CheckInError?=null) : ScannerAccess
    data class Denied(val error: CheckInError) : ScannerAccess
}
interface CheckInRepository {
    fun parseManual(raw:String):ManualCode? = ManualCodePolicy.parse(raw)
    fun observeAccess(eventId: EventId): Flow<ScannerAccess>
    suspend fun refreshAccess(eventId: EventId)
    suspend fun checkIn(eventId: EventId, credential: TicketCredential): CheckInOutcome
    suspend fun checkIn(eventId:EventId,command:ScanCommand):CheckInOutcome = checkIn(eventId,command.credential)
}

data class Volunteer(val grant: ScanGrant, val email: String, val checkedInCount: Int = 0,
    val publicId:String?=null,val fullName:String?=null,val grantedAt:Instant?=null)
enum class VolunteerError { FEATURE_UNAVAILABLE, AUTHENTICATION_REQUIRED, FORBIDDEN, NOT_FOUND, ACCOUNT_NOT_FOUND, INVALID_TARGET, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, PROTOCOL, SERVER_UNAVAILABLE, RATE_LIMITED, EVENT_FINISHED }
sealed interface VolunteersResult {
    data class Ready(val volunteers: List<Volunteer>) : VolunteersResult
    data class Failed(val error: VolunteerError) : VolunteersResult
}
sealed interface VolunteerOutcome {
    data class Updated(val grant: ScanGrant) : VolunteerOutcome
    data class Granted(val volunteer:Volunteer) : VolunteerOutcome
    data class Revoked(val userId:UserId) : VolunteerOutcome
    data class Failed(val error: VolunteerError,val retryAt:Instant?=null) : VolunteerOutcome
}
interface VolunteerRepository {
    fun observeVolunteers(eventId: EventId): Flow<VolunteersResult>
    suspend fun refreshVolunteers(eventId: EventId): VolunteersResult
    suspend fun grant(eventId: EventId, emailOrId: String): VolunteerOutcome
    suspend fun revoke(eventId: EventId, userId: UserId): VolunteerOutcome
}
