package ru.eventpass.data.fake.product

import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.RegistrationRepository
import ru.eventpass.core.domain.TicketRepository
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.Event
import ru.eventpass.core.domain.event.EventAccessPolicy
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.EventLifecycle
import ru.eventpass.core.domain.event.PublicEventResult
import ru.eventpass.core.domain.event.RegistrationAvailability
import ru.eventpass.core.domain.event.ScanGrant
import ru.eventpass.core.domain.event.TicketId
import ru.eventpass.core.domain.event.UserId
import ru.eventpass.core.domain.event.registrationAvailability
import ru.eventpass.core.domain.registration.GuestRegistrationCommand
import ru.eventpass.core.domain.registration.GuestRegistrationOutcome
import ru.eventpass.core.domain.registration.GuestTicketSnapshot
import ru.eventpass.core.domain.registration.MyTicketsResult
import ru.eventpass.core.domain.registration.Registration
import ru.eventpass.core.domain.registration.RegistrationError
import ru.eventpass.core.domain.registration.RegistrationPolicy
import ru.eventpass.core.domain.registration.RegistrationValidation
import ru.eventpass.core.domain.registration.RegistrationValidator
import ru.eventpass.core.domain.registration.Ticket
import ru.eventpass.core.domain.registration.TicketStatus

data class FakeTicketEmail(val email: String, val ticketId: TicketId)

/** One in-memory demo backend. It never owns the Android guest screen's saved state. */
class FakeEventPassEnvironment(
    private val clock: Clock = Clock.fixed(FakeFixtures.NOW, ZoneOffset.UTC),
    authStorage: FakeAuthStorage = VolatileAuthStorage,
    authPolicy: FakeAuthPolicy = FakeAuthPolicy(),
    authSecrets: FakeAuthSecrets = RandomAuthSecrets(),
    beforeAuthOperation: suspend (String) -> Unit = {},
    private val authClock: Clock = clock,
) {

    private val lock = Mutex()
    private val state = MutableStateFlow(seed())
    private val authBackend = FakeAuthRepository(state, lock, authClock, authStorage, authPolicy, authSecrets, beforeAuthOperation)
    val auth: ru.eventpass.core.domain.auth.AuthRepository = authBackend

    val events: EventRepository = object : EventRepository {
        override fun observePublicEvent(eventId: EventId): Flow<PublicEventResult> = state.map { current ->
            val event = current.events[eventId]
            val account = current.activeAccount(authClock.instant())
            if (event == null ||
                (event.lifecycle == EventLifecycle.DRAFT &&
                    !(account?.emailVerified == true && account.id == event.ownerId))
            ) PublicEventResult.NotFound
            else {
                val availability = event.registrationAvailability(clock.instant(), countTickets(current, eventId))
                PublicEventResult.Found(
                    event, availability,
                    EventAccessPolicy.resolve(
                        event, account,
                        account != null && current.tickets.any { ticket ->
                            ticket.status != TicketStatus.CANCELLED && ticket.snapshot.eventId == eventId &&
                                current.registrations.any {
                                    it.id == ticket.registrationId && it.attendeeEmail == account.email
                                }
                        },
                        current.grants, availability,
                    ),
                )
            }
        }.distinctUntilChanged()

        override suspend fun refreshPublicEvent(eventId: EventId) {
            // Re-evaluate Clock-dependent availability on foreground/refresh. No background timer.
            mutate { it.copy(revision = it.revision + 1) }
        }
    }

    val registrations: RegistrationRepository = object : RegistrationRepository {
        override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome =
            lock.withLock { issueGuestTicket(command) }
    }

    val tickets: TicketRepository = object : TicketRepository {
        override fun observeMyTickets(): Flow<MyTicketsResult> = state.map { current ->
            val account = current.activeAccount(authClock.instant())
            when {
                account == null -> MyTicketsResult.AuthenticationRequired
                !account.emailVerified -> MyTicketsResult.EmailVerificationRequired
                else -> {
                    val registrationIds = current.registrations.filter {
                        it.attendeeEmail == account.email
                    }.map { it.id }.toSet()
                    MyTicketsResult.Ready(current.tickets.filter { it.registrationId in registrationIds })
                }
            }
        }.distinctUntilChanged()
    }

    /** Debug/test controls, not authentication or organizer API implementations. */
    val scenarios = ScenarioController()

    inner class ScenarioController internal constructor() {
        val seed: String get() = FakeFixtures.SEED
        suspend fun reset() = authBackend.reset(seed())
        suspend fun signInAs(userId: UserId) = mutate {
            require(userId in it.accounts) { "Unknown demo account" }
            it.copy(auth = it.auth.copy(session = ru.eventpass.core.domain.auth.Session(
                java.util.UUID.randomUUID().toString(), it.accounts.getValue(userId), authClock.instant().plusSeconds(1800)),
                generation = it.auth.generation + 1))
        }
        suspend fun signOut() = mutate { it.copy(auth = it.auth.copy(session = null, generation = it.auth.generation + 1)) }
        suspend fun putAccount(account: Account) = mutate {
            require(it.accounts.values.none { existing ->
                existing.id != account.id && existing.email == account.email
            }) { "Duplicate demo email" }
            it.copy(accounts = it.accounts + (account.id to account))
        }
        suspend fun putEvent(event: Event) = mutate { it.copy(events = it.events + (event.id to event)) }
        suspend fun setGrant(grant: ScanGrant) = mutate {
            it.copy(grants = it.grants.filterNot { old ->
                old.eventId == grant.eventId && old.userId == grant.userId
            } + grant)
        }
        suspend fun setPolicy(policy: RegistrationPolicy) = mutate { it.copy(policy = policy) }
        suspend fun failNextGuestSubmission(error: RegistrationError) = mutate { it.copy(nextSubmissionError = error) }
        fun outbox(): List<FakeTicketEmail> = state.value.outbox.toList()
        fun authOutbox(): List<FakeAuthMail> = state.value.auth.mail.toList()
        suspend fun failNextAuth(error: ru.eventpass.core.domain.auth.AuthError) = mutate { it.copy(auth = it.auth.copy(nextError = error)) }
        fun accounts(): List<Account> = state.value.accounts.values.toList()
        fun registrationFor(ticketId: TicketId): Registration? {
            val current = state.value
            val ticket = current.tickets.singleOrNull { it.snapshot.ticketId == ticketId } ?: return null
            return current.registrations.single { it.id == ticket.registrationId }
        }
    }

    private fun issueGuestTicket(command: GuestRegistrationCommand): GuestRegistrationOutcome {
        val current = state.value
        current.nextSubmissionError?.let { error ->
            state.value = current.copy(nextSubmissionError = null)
            return GuestRegistrationOutcome.Failed(error)
        }
        val event = current.events[command.eventId]
            ?.takeIf { it.lifecycle != EventLifecycle.DRAFT }
            ?: return GuestRegistrationOutcome.Failed(RegistrationError.NotFound)
        val availability = event.registrationAvailability(clock.instant(), countTickets(current, event.id))
        if (availability is RegistrationAvailability.Closed) {
            return GuestRegistrationOutcome.Failed(RegistrationError.Unavailable(availability.reason))
        }
        if (command.formVersion != event.formVersion) {
            return GuestRegistrationOutcome.Failed(RegistrationError.StaleForm)
        }
        val validation = RegistrationValidator.validate(command, event.questions, current.policy)
        if (validation is RegistrationValidation.Invalid) {
            return GuestRegistrationOutcome.Failed(RegistrationError.Validation(validation.errors))
        }
        val contact = (validation as RegistrationValidation.Valid).value
        if (current.accounts.values.any { it.email == contact.email }) {
            return GuestRegistrationOutcome.RequiresLogin(contact.email)
        }
        val existingRegistrationIds = current.registrations.filter {
            it.eventId == event.id && it.attendeeEmail == contact.email
        }.map { it.id }.toSet()
        if (current.tickets.any { it.registrationId in existingRegistrationIds && it.status != TicketStatus.CANCELLED }) {
            return GuestRegistrationOutcome.ExistingRegistration(contact.email)
        }
        val number = current.nextId
        val registration = Registration(
            "guest-registration-$number", event.id, null, contact.email, contact.name, contact.phone,
            event.formVersion, contact.answers.toMap(), clock.instant(),
        )
        val snapshot = GuestTicketSnapshot(
            TicketId("guest-ticket-$number"), event.id, event.title, event.startsAt, event.zoneId,
            event.venue, contact.name, "DEMO-$number", "demo-qr-token-$number",
        )
        state.value = current.copy(
            registrations = current.registrations + registration,
            tickets = current.tickets + Ticket(snapshot, registration.id, TicketStatus.ACTIVE),
            outbox = current.outbox + FakeTicketEmail(contact.email, snapshot.ticketId),
            nextId = number + 1,
        )
        return GuestRegistrationOutcome.Issued(snapshot, contact.email)
    }

    private fun countTickets(current: FakeProductState, eventId: EventId): Int = current.tickets.count {
        it.snapshot.eventId == eventId && it.status != TicketStatus.CANCELLED
    }

    private suspend fun mutate(transform: (FakeProductState) -> FakeProductState) = lock.withLock {
        state.value = transform(state.value)
    }

    private fun seed(): FakeProductState {
        val events = FakeFixtures.events()
        return FakeProductState(
            accounts = FakeFixtures.accounts().associateBy { it.id },
            events = events.associateBy { it.id },
            registrations = FakeFixtures.registrations(),
            tickets = FakeFixtures.tickets(events),
            grants = FakeFixtures.grants(),
        )
    }
}

internal data class FakeProductState(
    val accounts: Map<UserId, Account>,
    val events: Map<EventId, Event>,
    val registrations: List<Registration>,
    val tickets: List<Ticket>,
    val grants: List<ScanGrant>,
    val auth: AuthData = AuthData(),
    val outbox: List<FakeTicketEmail> = emptyList(),
    val policy: RegistrationPolicy = RegistrationPolicy(),
    val nextSubmissionError: RegistrationError? = null,
    val nextId: Long = 1,
    val revision: Long = 0,
)

internal fun FakeProductState.activeAccount(now: java.time.Instant): Account? = auth.session
    ?.takeIf { auth.healthy && now < it.expiresAt }?.let { accounts[it.account.id] }
