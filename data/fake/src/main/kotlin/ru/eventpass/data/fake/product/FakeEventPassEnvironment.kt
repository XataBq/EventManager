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

/** One shared demo backend with a versioned server snapshot, independent of guest UI state. */
class FakeEventPassEnvironment(
    private val clock: Clock = Clock.fixed(FakeFixtures.NOW, ZoneOffset.UTC),
    private val authStorage: FakeAuthStorage = VolatileAuthStorage,
    authPolicy: FakeAuthPolicy = FakeAuthPolicy(),
    authSecrets: FakeAuthSecrets = RandomAuthSecrets(),
    beforeAuthOperation: suspend (String) -> Unit = {},
    private val authClock: Clock = clock,
) {

    private val lock = Mutex()
    private val state = MutableStateFlow(seed())
    private val authBackend = FakeAuthRepository(state, lock, authClock, authStorage, authPolicy, authSecrets, beforeAuthOperation)
    val auth: ru.eventpass.core.domain.auth.AuthRepository = authBackend

    private val product = FakeTicketOperations(state, lock, clock, authClock, authStorage, authBackend)
    val events: EventRepository = product.events
    val registrations: RegistrationRepository = product.registrations
    val tickets: TicketRepository = product.tickets

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
        suspend fun putAccount(account: Account) = mutate(true) {
            require(it.accounts.values.none { existing ->
                existing.id != account.id && existing.email == account.email
            }) { "Duplicate demo email" }
            it.copy(accounts = it.accounts + (account.id to account))
        }
        suspend fun putEvent(event: Event) = mutate(true) { it.copy(events = it.events + (event.id to event)) }
        suspend fun setGrant(grant: ScanGrant) = mutate(true) {
            it.copy(grants = it.grants.filterNot { old ->
                old.eventId == grant.eventId && old.userId == grant.userId
            } + grant)
        }
        suspend fun setPolicy(policy: RegistrationPolicy) = mutate(true) { it.copy(policy = policy) }
        suspend fun failNextGuestSubmission(error: RegistrationError) = mutate { it.copy(nextSubmissionError = error) }
        suspend fun failNextRead(error: ru.eventpass.core.domain.event.ProductReadError) = mutate { it.copy(nextReadError = error) }
        suspend fun failNextCancellation(error: ru.eventpass.core.domain.registration.TicketError) = mutate { it.copy(nextTicketError = error) }
        suspend fun setTicketStatus(id: TicketId, status: TicketStatus) = mutate(true) {
            it.copy(tickets = it.tickets.map { ticket -> if (ticket.snapshot.ticketId == id) ticket.copy(status = status) else ticket })
        }
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

    private suspend fun mutate(persist: Boolean = false, transform: (FakeProductState) -> FakeProductState) {
        authBackend.initialize()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lock.withLock {
            val next = transform(state.value)
            if (persist) authStorage.write(ProductSnapshotCodec.encode(next))
            state.value = next
        } }
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
    val nextReadError: ru.eventpass.core.domain.event.ProductReadError? = null,
    val eventErrors: Map<EventId, ru.eventpass.core.domain.event.ProductReadError> = emptyMap(),
    val ticketReadError: ru.eventpass.core.domain.event.ProductReadError? = null,
    val nextTicketError: ru.eventpass.core.domain.registration.TicketError? = null,
)

internal fun FakeProductState.activeAccount(now: java.time.Instant): Account? = auth.session
    ?.takeIf { auth.healthy && now < it.expiresAt }?.let { accounts[it.account.id] }
