package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

/** Operations on the environment's single state/Mutex; no second backend or guest lookup API. */
internal class FakeTicketOperations(
    private val state: MutableStateFlow<FakeProductState>, private val lock: Mutex,
    private val clock: Clock, private val authClock: Clock,
    private val storage: FakeAuthStorage, private val auth: FakeAuthRepository,
) {
    private fun initialized(): Flow<FakeProductState> = flow { auth.initialize(); emitAll(state) }
    private val editing = FakeEventEditingOperations(state, lock, clock, authClock, storage, auth)
    val events = object : EventRepository {
        override fun observeMyEvents() = editing.observeMyEvents()
        override fun observeEditor(target: EventEditorTarget) = editing.observeEditor(target)
        override suspend fun refreshMyEvents() = editing.refreshMyEvents()
        override suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields) = editing.saveDraft(id, expectedRevision, fields)
        override suspend fun publishDraft(id: DraftId, expectedRevision: Long) = editing.publishDraft(id, expectedRevision)
        override suspend fun updateEvent(id: EventId, expectedRevision: Long, expectedFormVersion: Int, fields: EventFields) = editing.updateEvent(id, expectedRevision, expectedFormVersion, fields)
        override suspend fun closeRegistration(id: EventId, expectedRevision: Long) = editing.closeRegistration(id, expectedRevision)
        override suspend fun finishEvent(id: EventId, expectedRevision: Long) = editing.finishEvent(id, expectedRevision)
        override fun observePublicEvent(eventId: EventId): Flow<PublicEventResult> = initialized().map { s ->
            val event = s.events[eventId]; val account = s.activeAccount(authClock.instant())
            when {
                !s.auth.healthy -> PublicEventResult.Failed(ProductReadError.STORAGE_UNAVAILABLE)
                eventId in s.eventErrors -> PublicEventResult.Failed(s.eventErrors.getValue(eventId))
                event == null || (event.lifecycle == EventLifecycle.DRAFT && !(account?.emailVerified == true && account.id == event.ownerId)) -> PublicEventResult.NotFound
                else -> {
                    val availability = event.registrationAvailability(clock.instant(), count(s, eventId))
                    PublicEventResult.Found(event, availability, EventAccessPolicy.resolve(event, account,
                        account != null && own(s, account).any { it.snapshot.eventId == eventId && it.status != TicketStatus.CANCELLED }, s.grants, availability),
                        s.policy, account?.takeIf { it.emailVerified }?.let { RegistrationContact(it.email, s.profileFields(it.id).name, s.profileFields(it.id).phone) })
                }
            }
        }.distinctUntilChanged()
        override suspend fun refreshPublicEvent(eventId: EventId) { update {
            it.copy(eventErrors = if (it.nextReadError == null) it.eventErrors - eventId else it.eventErrors + (eventId to it.nextReadError), nextReadError = null, revision = it.revision + 1)
        } }
    }
    val registrations = object : RegistrationRepository {
        override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome =
            transaction<GuestRegistrationOutcome>(GuestRegistrationOutcome.Failed(RegistrationError.StorageUnavailable)) { s ->
                s.nextSubmissionError?.let { return@transaction s.copy(nextSubmissionError = null) to GuestRegistrationOutcome.Failed(it) }
                validate(s, command)?.let { return@transaction s to GuestRegistrationOutcome.Failed(it) }
                val contact = validated(s, command)
                when {
                    s.accounts.values.any { it.email == contact.email } -> s to GuestRegistrationOutcome.RequiresLogin(contact.email)
                    existing(s, command.eventId, contact.email) != null -> s to GuestRegistrationOutcome.ExistingRegistration(contact.email)
                    else -> { val (next, ticket) = issue(s, command, contact, null); next to GuestRegistrationOutcome.Issued(ticket.snapshot, contact.email) }
                }
            }
        override suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand): AuthenticatedRegistrationOutcome {
            auth.initialize(); val generation = state.value.auth.generation
            return transaction<AuthenticatedRegistrationOutcome>(AuthenticatedRegistrationOutcome.Failed(RegistrationError.StorageUnavailable)) { s ->
                if (generation != s.auth.generation) return@transaction s to AuthenticatedRegistrationOutcome.Failed(RegistrationError.SessionChanged)
                val account = s.activeAccount(authClock.instant())?.takeIf { it.emailVerified }
                    ?: return@transaction s to AuthenticatedRegistrationOutcome.Failed(RegistrationError.AuthenticationRequired)
                existing(s, command.eventId, account.email)?.let { return@transaction s to AuthenticatedRegistrationOutcome.Registered(it.snapshot.ticketId, true) }
                s.nextSubmissionError?.let { return@transaction s.copy(nextSubmissionError = null) to AuthenticatedRegistrationOutcome.Failed(it) }
                val guest = GuestRegistrationCommand(command.eventId, command.formVersion, command.name, account.email, command.phone, command.answers, command.consentAccepted)
                val error = validate(s, guest)
                if (error != null) s to AuthenticatedRegistrationOutcome.Failed(error)
                else { val (next, ticket) = issue(s, guest, validated(s, guest), account.id); next to AuthenticatedRegistrationOutcome.Registered(ticket.snapshot.ticketId, false) }
            }
        }
    }
    val tickets = object : TicketRepository {
        override fun observeMyTickets(): Flow<MyTicketsResult> = initialized().map { s ->
            val account = s.activeAccount(authClock.instant())
            when {
                !s.auth.healthy -> MyTicketsResult.Failed(ProductReadError.STORAGE_UNAVAILABLE)
                account == null -> MyTicketsResult.AuthenticationRequired
                !account.emailVerified -> MyTicketsResult.EmailVerificationRequired
                s.ticketReadError != null -> MyTicketsResult.Failed(s.ticketReadError)
                else -> {
                    val mine = own(s, account)
                    val eventIds = mine.map { it.snapshot.eventId }.toSet()
                    MyTicketsResult.Ready(mine, s.events.filterKeys { it in eventIds }, clock.instant())
                }
            }
        }.distinctUntilChanged()
        override fun observeOwnTicket(ticketId: TicketId): Flow<OwnTicketResult> = observeMyTickets().map { result -> when (result) {
            is MyTicketsResult.Ready -> result.tickets.singleOrNull { it.snapshot.ticketId == ticketId }?.let { t -> result.events[t.snapshot.eventId]?.let { OwnTicketResult.Found(t, it, result.now) } } ?: OwnTicketResult.NotFound
            is MyTicketsResult.Failed -> OwnTicketResult.Failed(result.error)
            else -> OwnTicketResult.AuthenticationRequired
        } }
        override suspend fun refreshMyTickets() { update { it.copy(ticketReadError = it.nextReadError, nextReadError = null, revision = it.revision + 1) } }
        override suspend fun cancelOwnTicket(ticketId: TicketId): CancelTicketOutcome {
            auth.initialize(); val generation = state.value.auth.generation
            return transaction<CancelTicketOutcome>(CancelTicketOutcome.Failed(TicketError.STORAGE_UNAVAILABLE)) { s ->
                if (generation != s.auth.generation) return@transaction s to CancelTicketOutcome.Failed(TicketError.SESSION_CHANGED)
                val account = s.activeAccount(authClock.instant())?.takeIf { it.emailVerified }
                    ?: return@transaction s to CancelTicketOutcome.Failed(TicketError.AUTHENTICATION_REQUIRED)
                val ticket = own(s, account).singleOrNull { it.snapshot.ticketId == ticketId } ?: return@transaction s to CancelTicketOutcome.Failed(TicketError.NOT_FOUND)
                if (ticket.status == TicketStatus.USED) return@transaction s to CancelTicketOutcome.Failed(TicketError.USED)
                s.nextTicketError?.let { return@transaction s.copy(nextTicketError = null) to CancelTicketOutcome.Failed(it) }
                s.cancelTicket(ticket)
            }
        }
    }
    private fun own(s: FakeProductState, account: Account): List<Ticket> {
        val ids = s.registrations.filter { it.attendeeEmail == account.email }.map { it.id }.toSet()
        return s.tickets.filter { it.registrationId in ids }
    }
    private fun existing(s: FakeProductState, eventId: EventId, email: String): Ticket? {
        val ids = s.registrations.filter { it.eventId == eventId && it.attendeeEmail == email }.map { it.id }.toSet()
        return s.tickets.firstOrNull { it.registrationId in ids && it.status != TicketStatus.CANCELLED }
    }
    private fun validate(s: FakeProductState, command: GuestRegistrationCommand): RegistrationError? {
        val event = s.events[command.eventId]?.takeIf { it.lifecycle != EventLifecycle.DRAFT } ?: return RegistrationError.NotFound
        val availability = event.registrationAvailability(clock.instant(), count(s, event.id))
        if (availability is RegistrationAvailability.Closed) return RegistrationError.Unavailable(availability.reason)
        if (command.formVersion != event.formVersion) return RegistrationError.StaleForm
        return (RegistrationValidator.validate(command, event.questions, s.policy) as? RegistrationValidation.Invalid)?.let { RegistrationError.Validation(it.errors) }
    }
    private fun validated(s: FakeProductState, command: GuestRegistrationCommand) = (RegistrationValidator.validate(command, s.events.getValue(command.eventId).questions, s.policy) as RegistrationValidation.Valid).value
    private fun issue(s: FakeProductState, command: GuestRegistrationCommand, contact: ValidatedRegistration, userId: UserId?): Pair<FakeProductState, Ticket> {
        val event = s.events.getValue(command.eventId); val number = s.nextId
        val registration = Registration("registration-$number", event.id, userId, contact.email, contact.name, contact.phone, event.formVersion, contact.answers.toMap(), clock.instant())
        val snapshot = GuestTicketSnapshot(TicketId("ticket-$number"), event.id, event.title, event.startsAt, event.zoneId, event.venue, contact.name, "DEMO-$number", "eventpass-demo-ticket:${UUID.randomUUID()}")
        val ticket = Ticket(snapshot, registration.id, TicketStatus.ACTIVE)
        return s.copy(registrations = s.registrations + registration, tickets = s.tickets + ticket, outbox = s.outbox + FakeTicketEmail(contact.email, snapshot.ticketId), nextId = number + 1) to ticket
    }
    private fun count(s: FakeProductState, eventId: EventId) = s.tickets.count { it.snapshot.eventId == eventId && it.status != TicketStatus.CANCELLED }
    /** Persist before publication; all IO and shared Mutex waiting free the caller's thread. */
    private suspend fun <T> transaction(storageFailure: T, action: (FakeProductState) -> Pair<FakeProductState, T>): T {
        auth.initialize()
        return withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive()
            val s = state.value
            if (!s.auth.healthy) return@withLock storageFailure
            val (next, result) = action(s)
            if (next != s) {
                try { storage.write(ProductSnapshotCodec.encode(next)) } catch (_: IOException) { return@withLock storageFailure }
                state.value = next
            }
            result
        } }
    }
    private suspend fun update(change: (FakeProductState) -> FakeProductState) {
        auth.initialize(); withContext(Dispatchers.IO) { lock.withLock { state.value = change(state.value) } }
    }
}
