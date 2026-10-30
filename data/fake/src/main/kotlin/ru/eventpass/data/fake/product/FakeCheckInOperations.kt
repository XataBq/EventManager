package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.TicketStatus

/** One transaction path for grants/check-in, using the existing product/auth state and storage. */
internal class FakeCheckInOperations(private val state: MutableStateFlow<FakeProductState>, private val lock: Mutex,
    private val clock: Clock, private val authClock: Clock, private val storage: FakeAuthStorage, private val auth: FakeAuthRepository) {
    private fun initialized() = flow { auth.initialize(); emitAll(state) }
    private fun access(s: FakeProductState, id: EventId, manage: Boolean = false, now: java.time.Instant = authClock.instant()): CheckInError? {
        if (!s.auth.healthy) return CheckInError.STORAGE_UNAVAILABLE
        val account = s.activeAccount(now)?.takeIf { it.emailVerified } ?: return CheckInError.AUTHENTICATION_REQUIRED
        val event = s.events[id] ?: return CheckInError.NOT_FOUND
        val capabilities = EventAccessPolicy.resolve(event, account, false, s.grants, event.registrationAvailability(clock.instant(), 0))
        return if (event.lifecycle == EventLifecycle.DRAFT || !(if (manage) capabilities.canManageVolunteers else capabilities.canScan)) CheckInError.FORBIDDEN else null
    }
    val checkIns = object : CheckInRepository {
        override fun observeAccess(eventId: EventId): Flow<ScannerAccess> = initialized().map { s ->
            val now = authClock.instant()
            access(s, eventId, now = now)?.let { return@map ScannerAccess.Denied(it) }
            ScannerAccess.Allowed(eventId, s.events.getValue(eventId).title,
                ScannerScope(s.activeAccount(now)!!.id, s.auth.generation))
        }.distinctUntilChanged()
        override suspend fun refreshAccess(eventId: EventId) {
            auth.initialize(); withContext(Dispatchers.IO) { lock.withLock { state.value = state.value.copy(revision = state.value.revision + 1) } }
        }
        override suspend fun checkIn(eventId: EventId, credential: TicketCredential): CheckInOutcome {
            auth.initialize(); val generation = state.value.auth.generation
            return mutate(CheckInOutcome.Failed(CheckInError.STORAGE_UNAVAILABLE)) { s ->
                if (generation != s.auth.generation) return@mutate s to CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
                access(s, eventId)?.let { return@mutate s to CheckInOutcome.Failed(it) }
                s.nextCheckInError?.let { return@mutate s.copy(nextCheckInError = null) to CheckInOutcome.Failed(it) }
                val manual = (credential as? TicketCredential.Manual)?.let { ManualCodePolicy.parse(it.code.value) }
                if ((credential is TicketCredential.Manual && manual == null) ||
                    (credential is TicketCredential.Qr && (credential.token.value.isBlank() || credential.token.value.length > 4096)))
                    return@mutate s to CheckInOutcome.Failed(CheckInError.INVALID_INPUT)
                val ticket = s.tickets.singleOrNull { t -> when (credential) {
                    is TicketCredential.Qr -> t.snapshot.qrPayload == credential.token.value
                    is TicketCredential.Manual -> t.snapshot.manualCode == manual
                } } ?: return@mutate s to CheckInOutcome.Invalid(InvalidTicketReason.UNKNOWN)
                if (ticket.snapshot.eventId != eventId) return@mutate s to CheckInOutcome.Invalid(InvalidTicketReason.WRONG_EVENT)
                if (ticket.status == TicketStatus.CANCELLED) return@mutate s to CheckInOutcome.Invalid(InvalidTicketReason.CANCELLED)
                if (ticket.status == TicketStatus.USED) return@mutate s to CheckInOutcome.AlreadyUsed(s.checkIns.singleOrNull { it.ticketId == ticket.snapshot.ticketId })
                val event = s.events.getValue(eventId)
                if (event.lifecycle != EventLifecycle.PUBLISHED || event.endsAt?.let { clock.instant() >= it } == true)
                    return@mutate s to CheckInOutcome.Invalid(InvalidTicketReason.EVENT_INACTIVE)
                val checker = s.activeAccount(authClock.instant()) ?: return@mutate s to CheckInOutcome.Failed(CheckInError.AUTHENTICATION_REQUIRED)
                val record = CheckInRecord("checkin-${UUID.randomUUID()}", eventId, ticket.snapshot.ticketId, checker.id,
                    clock.instant(), ticket.snapshot.attendeeName, ticket.snapshot.displayNumber)
                s.copy(checkIns = s.checkIns + record, tickets = s.tickets.map { if (it.snapshot.ticketId == ticket.snapshot.ticketId) it.copy(status = TicketStatus.USED) else it }) to CheckInOutcome.Admitted(record)
            }
        }
    }
    val volunteers = object : VolunteerRepository {
        override fun observeVolunteers(eventId: EventId): Flow<VolunteersResult> = initialized().map { s ->
            access(s, eventId, true)?.let { return@map VolunteersResult.Failed(it.volunteer()) }
            VolunteersResult.Ready(s.grants.filter { it.eventId == eventId && it.active }.map { Volunteer(it, s.accounts.getValue(it.userId).email) })
        }.distinctUntilChanged()
        override suspend fun grant(eventId: EventId, emailOrId: String): VolunteerOutcome {
            val target = emailOrId.trim()
            return change(eventId) { s ->
                if (target.isEmpty() || target.length > 320) return@change s to VolunteerOutcome.Failed(VolunteerError.INVALID_TARGET)
                val account = s.accounts.values.singleOrNull { it.email == target.lowercase(Locale.ROOT) || it.id.value == target }
                    ?: return@change s to VolunteerOutcome.Failed(VolunteerError.ACCOUNT_NOT_FOUND)
                updateGrant(s, ScanGrant(eventId, account.id, true))
            }
        }
        override suspend fun revoke(eventId: EventId, userId: UserId): VolunteerOutcome = change(eventId) { s ->
            if (userId !in s.accounts) s to VolunteerOutcome.Failed(VolunteerError.ACCOUNT_NOT_FOUND)
            else updateGrant(s, ScanGrant(eventId, userId, false))
        }
        private suspend fun change(eventId: EventId, action: (FakeProductState) -> Pair<FakeProductState, VolunteerOutcome>): VolunteerOutcome {
            auth.initialize(); val generation = state.value.auth.generation
            return mutate(VolunteerOutcome.Failed(VolunteerError.STORAGE_UNAVAILABLE)) { s ->
                if (generation != s.auth.generation) return@mutate s to VolunteerOutcome.Failed(VolunteerError.SESSION_CHANGED)
                access(s, eventId, true)?.let { return@mutate s to VolunteerOutcome.Failed(it.volunteer()) }
                s.nextVolunteerError?.let { return@mutate s.copy(nextVolunteerError = null) to VolunteerOutcome.Failed(it) }
                action(s)
            }
        }
    }
    private fun updateGrant(s: FakeProductState, grant: ScanGrant): Pair<FakeProductState, VolunteerOutcome> {
        if (s.grants.any { it == grant }) return s to VolunteerOutcome.Updated(grant)
        return s.copy(grants = s.grants.filterNot { it.eventId == grant.eventId && it.userId == grant.userId } + grant) to VolunteerOutcome.Updated(grant)
    }
    private suspend fun <T> mutate(storageFailure: T, action: (FakeProductState) -> Pair<FakeProductState, T>): T = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            val old = state.value
            if (!old.auth.healthy) return@withLock storageFailure
            val (next, result) = action(old)
            if (next != old) {
                try { storage.write(ProductSnapshotCodec.encode(next)) }
                catch (_: IOException) { return@withLock storageFailure }
                catch (_: IllegalArgumentException) { return@withLock storageFailure }
                state.value = next
            }
            result
        }
    }
}

private fun CheckInError.volunteer() = VolunteerError.valueOf(name)
