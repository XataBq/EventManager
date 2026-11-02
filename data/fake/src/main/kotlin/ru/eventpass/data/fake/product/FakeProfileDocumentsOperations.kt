package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.registration.*

/** Name has exactly one source of truth: AuthData.names; identity/session never change on save. */
internal data class ProfileDetails(val phone: String? = null, val organization: String? = null, val notifications: Boolean = true)
internal fun FakeProductState.profileFields(id: UserId): ProfileFields {
    val details = profileDetails[id] ?: ProfileDetails()
    return ProfileFields(auth.names[id].orEmpty(), details.phone, details.organization, details.notifications)
}
data class FakeDocumentsPolicy(val resendCooldown: Duration = Duration.ofMinutes(1)) {
    init { require(resendCooldown > Duration.ZERO) }
}
internal class FakeProfileDocumentsOperations(private val state: MutableStateFlow<FakeProductState>, private val lock: Mutex,
    private val clock: Clock, private val authClock: Clock, private val storage: FakeAuthStorage,
    private val auth: FakeAuthRepository, private val policy: FakeDocumentsPolicy,
    private val beforeOperation: suspend (String) -> Unit) : ProfileRepository, DocumentsRepository {
    private fun profile(s: FakeProductState): ProfileResult {
        if (!s.auth.healthy) return ProfileResult.Failed(ProfileError.STORAGE_UNAVAILABLE)
        val actor = s.activeAccount(authClock.instant())?.takeIf { it.emailVerified }
            ?: return ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED)
        return ProfileResult.Ready(Profile(actor, s.profileFields(actor.id)))
    }
    override fun observeProfile(): Flow<ProfileResult> = flow { auth.initialize(); emitAll(state) }
        .map(::profile).distinctUntilChanged()
    override suspend fun saveProfile(fields: ProfileFields): ProfileResult {
        auth.initialize(); val generation = state.value.auth.generation; beforeOperation("profile")
        return withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive(); val s = state.value
            if (s.auth.generation != generation) return@withLock ProfileResult.Failed(ProfileError.SESSION_CHANGED)
            val current = profile(s); if (current !is ProfileResult.Ready) return@withLock current
            if (!ProfilePolicy.valid(fields)) return@withLock ProfileResult.Failed(ProfileError.INVALID_INPUT)
            s.nextProfileError?.let { state.value = s.copy(nextProfileError = null); return@withLock ProfileResult.Failed(it) }
            val value = ProfilePolicy.normalized(fields); val id = current.profile.account.id
            val next = s.copy(auth = s.auth.copy(names = s.auth.names + (id to value.name)),
                profileDetails = s.profileDetails + (id to ProfileDetails(value.phone, value.organization, value.emailNotifications)))
            try { storage.write(ProductSnapshotCodec.encode(next)) }
            catch (_: IOException) { return@withLock ProfileResult.Failed(ProfileError.STORAGE_UNAVAILABLE) }
            // The durable profile committed. Cancellation may suppress the UI reply, never undo the disk commit.
            state.value = next; profile(next)
        } }
    }
    private fun access(s: FakeProductState, request: DocumentRequest): DocumentError? {
        if (!s.auth.healthy) return DocumentError.STORAGE_UNAVAILABLE
        val actor = s.activeAccount(authClock.instant())?.takeIf { it.emailVerified } ?: return DocumentError.AUTHENTICATION_REQUIRED
        return when (request) {
            is DocumentRequest.TicketPdf -> {
                val t = s.tickets.singleOrNull { it.snapshot.ticketId == request.ticketId } ?: return DocumentError.NOT_FOUND
                val r = s.registrations.singleOrNull { it.id == t.registrationId && it.attendeeEmail == actor.email }
                    ?: return DocumentError.FORBIDDEN
                val e = s.events[r.eventId] ?: return DocumentError.NOT_FOUND
                if (!t.canDisplayQr(e, clock.instant())) DocumentError.NOT_CURRENT else null
            }
            is DocumentRequest.AttendanceXlsx -> {
                val e = s.events[request.eventId] ?: return DocumentError.NOT_FOUND
                if (EventAccessPolicy.resolve(e, actor, false, s.grants, e.registrationAvailability(clock.instant(), 0)).canExport) null
                else DocumentError.FORBIDDEN
            }
        }
    }
    override suspend fun prepare(request: DocumentRequest): DocumentResult {
        auth.initialize(); val generation = state.value.auth.generation; beforeOperation("document")
        return withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive(); val s = state.value
            if (s.auth.generation != generation) return@withLock DocumentResult.Failed(DocumentError.SESSION_CHANGED)
            access(s, request)?.let { return@withLock DocumentResult.Failed(it) }
            s.nextDocumentError?.let { state.value = s.copy(nextDocumentError = null); return@withLock DocumentResult.Failed(it) }
            val stamp = DocumentStamp(s.auth.session!!.account.id, generation)
            DocumentResult.Ready(when (request) {
                is DocumentRequest.TicketPdf -> DocumentSnapshot.Ticket(stamp, request, s.tickets.single { it.snapshot.ticketId == request.ticketId }.snapshot)
                is DocumentRequest.AttendanceXlsx -> {
                    val records = s.checkIns.associateBy { it.ticketId }
                    val tickets = s.tickets.associateBy { it.registrationId }
                    val rows = s.registrations.filter { it.eventId == request.eventId }
                        .sortedWith(compareByDescending<Registration> { it.consentAcceptedAt }.thenBy { it.id }).map { r ->
                            val t = tickets.getValue(r.id); val first = records[t.snapshot.ticketId]
                            ExportAttendee(r, t.snapshot.displayNumber, t.status, first?.checkedAt, first?.checkerId)
                        }
                    DocumentSnapshot.Attendance(stamp, request, s.events.getValue(request.eventId), clock.instant(), rows)
                }
            })
        } }
    }
    override suspend fun validate(snapshot: DocumentSnapshot): DocumentError? {
        auth.initialize(); val s = state.value
        if (s.auth.generation != snapshot.stamp.generation || s.auth.session?.account?.id != snapshot.stamp.accountId)
            return DocumentError.SESSION_CHANGED
        access(s, snapshot.request)?.let { return it }
        if (snapshot is DocumentSnapshot.Ticket && s.tickets.single { it.snapshot.ticketId == snapshot.request.ticketId }.snapshot != snapshot.ticket)
            return DocumentError.NOT_CURRENT
        return null
    }
    override suspend fun resendTicket(ticketId: TicketId): ResendResult {
        auth.initialize(); val generation = state.value.auth.generation; beforeOperation("resend")
        return withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive(); val s = state.value
            if (s.auth.generation != generation) return@withLock ResendResult.Failed(DocumentError.SESSION_CHANGED)
            access(s, DocumentRequest.TicketPdf(ticketId))?.let { return@withLock ResendResult.Failed(it) }
            s.nextDocumentError?.let { state.value = s.copy(nextDocumentError = null); return@withLock ResendResult.Failed(it) }
            val now = authClock.instant()
            s.ticketResendAt[ticketId]?.takeIf { now < it }?.let { return@withLock ResendResult.Cooldown(it) }
            val t = s.tickets.single { it.snapshot.ticketId == ticketId }; val r = s.registrations.single { it.id == t.registrationId }
            val retryAt = now.plus(policy.resendCooldown)
            state.value = s.copy(outbox = s.outbox + FakeTicketEmail(r.attendeeEmail, ticketId), ticketResendAt = s.ticketResendAt + (ticketId to retryAt))
            ResendResult.Queued(retryAt)
        } }
    }
}
