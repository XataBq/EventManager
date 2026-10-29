package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

class TicketPersistenceTest {
    private class Storage : FakeAuthStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.copyOf()
        override fun write(snapshot: ByteArray) { if (fail) throw IOException("Test storage failure"); bytes = snapshot.copyOf() }
    }
    private val policy = FakeAuthPolicy(passwordIterations = 1000)
    private fun backend(storage: Storage) = FakeEventPassEnvironment(authStorage = storage, authPolicy = policy)
    private fun guest(email: String = "guest@example.com") = GuestRegistrationCommand(FakeFixtures.OPEN, 1, "李 Мария", email, consentAccepted = true)
    private fun member() = AuthenticatedRegistrationCommand(FakeFixtures.OPEN, 1, "Alex", consentAccepted = true)
    private suspend fun login(e: FakeEventPassEnvironment, email: String = "alex@example.com", password: String = "DemoPass123!") {
        assertTrue(e.auth.signIn(email, AuthSecret(password)) is AuthResult.Success)
    }
    private suspend fun remaining(e: FakeEventPassEnvironment) =
        ((e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found).availability as RegistrationAvailability.Open).places

    @Test fun guestThenSeparateSignupAndRestartRestoresOnlyOwnTicketsAfterLogin() = runTest {
        val storage = Storage(); val first = backend(storage)
        val receipt = first.registrations.submitGuest(guest()) as GuestRegistrationOutcome.Issued
        assertEquals(2, first.scenarios.accounts().size)
        val restarted = backend(storage)
        assertEquals(SessionState.Guest, restarted.auth.observeSession().first().state)
        assertTrue(restarted.scenarios.outbox().isEmpty())
        assertEquals(GuestRegistrationOutcome.ExistingRegistration("guest@example.com"), restarted.registrations.submitGuest(guest()))
        val challenge = (restarted.auth.signUp("李", "guest@example.com", AuthSecret("GuestPass123!")) as AuthResult.Success).value
        val mail = restarted.scenarios.authOutbox().last()
        assertTrue(restarted.auth.confirmEmail(challenge.id, mail.secret) is AuthResult.Success)
        assertEquals(SessionState.Guest, restarted.auth.currentSession().state)
        login(restarted, "guest@example.com", "GuestPass123!")
        assertEquals(listOf(receipt.ticket), (restarted.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.map { it.snapshot })
        restarted.auth.signOut()
        login(restarted)
        assertEquals(OwnTicketResult.NotFound, restarted.tickets.observeOwnTicket(receipt.ticket.ticketId).first())
        assertEquals(CancelTicketOutcome.Failed(TicketError.NOT_FOUND), restarted.tickets.cancelOwnTicket(receipt.ticket.ticketId))
        val third = backend(storage); login(third, "guest@example.com", "GuestPass123!")
        assertTrue(third.tickets.observeOwnTicket(receipt.ticket.ticketId).first() is OwnTicketResult.Found)
    }

    @Test fun authenticatedIdentityDuplicateCancelAndNewTokenAreRepositoryDecisions() = runTest {
        val e = backend(Storage()); login(e)
        val first = e.registrations.submitAuthenticated(member()) as AuthenticatedRegistrationOutcome.Registered
        assertEquals(false, first.existing)
        assertEquals(first.copy(existing = true), e.registrations.submitAuthenticated(member()))
        assertEquals(AvailablePlaces.Limited(19), remaining(e))
        assertEquals("alex@example.com", e.scenarios.registrationFor(first.ticketId)?.attendeeEmail)
        val old = (e.tickets.observeOwnTicket(first.ticketId).first() as OwnTicketResult.Found).ticket.snapshot.qrPayload
        assertTrue(e.tickets.cancelOwnTicket(first.ticketId) is CancelTicketOutcome.Cancelled)
        assertTrue(e.tickets.cancelOwnTicket(first.ticketId) is CancelTicketOutcome.Cancelled)
        assertEquals(AvailablePlaces.Limited(20), remaining(e))
        val again = e.registrations.submitAuthenticated(member()) as AuthenticatedRegistrationOutcome.Registered
        assertNotEquals(first.ticketId, again.ticketId)
        assertNotEquals(old, (e.tickets.observeOwnTicket(again.ticketId).first() as OwnTicketResult.Found).ticket.snapshot.qrPayload)
        assertEquals(AvailablePlaces.Limited(19), remaining(e))
    }

    @Test fun usedCannotBeCancelledAndGrantRevocationLeavesParticipation() = runTest {
        val e = backend(Storage()); login(e)
        val id = TicketId("seed-ticket-alex-mixed")
        e.scenarios.setTicketStatus(id, TicketStatus.USED)
        e.scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, false))
        assertEquals(CancelTicketOutcome.Failed(TicketError.USED), e.tickets.cancelOwnTicket(id))
        val ticket = e.tickets.observeOwnTicket(id).first() as OwnTicketResult.Found
        assertFalse(ticket.ticket.canDisplayQr(ticket.event, ticket.now))
        val access = (e.events.observePublicEvent(FakeFixtures.MIXED).first() as PublicEventResult.Found).access
        assertFalse(access.canScan); assertTrue(access.canViewOwnTicket)
    }

    @Test fun failedStoragePublishesNeitherTicketMailNorFreedPlace() = runTest {
        val storage = Storage(); val e = backend(storage)
        e.auth.refreshSession(); storage.fail = true
        assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.StorageUnavailable), e.registrations.submitGuest(guest()))
        assertTrue(e.scenarios.outbox().isEmpty()); assertEquals(AvailablePlaces.Limited(20), remaining(e))
        storage.fail = false; login(e)
        val registered = e.registrations.submitAuthenticated(member()) as AuthenticatedRegistrationOutcome.Registered
        storage.fail = true
        assertEquals(CancelTicketOutcome.Failed(TicketError.STORAGE_UNAVAILABLE), e.tickets.cancelOwnTicket(registered.ticketId))
        assertEquals(TicketStatus.ACTIVE, (e.tickets.observeOwnTicket(registered.ticketId).first() as OwnTicketResult.Found).ticket.status)
        assertEquals(AvailablePlaces.Limited(19), remaining(e))
    }

    @Test fun legacyAuthSchemaMigratesOnWriteWithoutLosingProofOrChallenge() = runTest {
        val storage = Storage(); val clock = Clock.fixed(FakeFixtures.NOW, ZoneOffset.UTC)
        val initial = FakeProductState(FakeFixtures.accounts().associateBy { it.id }, FakeFixtures.events().associateBy { it.id },
            FakeFixtures.registrations(), FakeFixtures.tickets(FakeFixtures.events()), FakeFixtures.grants())
        val token = AuthSecret("legacy-token")
        val challenge = StoredChallenge("legacy-challenge", FakeFixtures.ALEX, false, digest(token), clock.instant().plusSeconds(900), clock.instant())
        val auth = AuthData(passwords = initial.accounts.mapValues { passwordProof(AuthSecret("LegacyPass123!"), 1000) }, challenges = mapOf(challenge.id to challenge))
        storage.bytes = AuthSnapshotCodec.encode(initial.accounts, auth)
        val e = backend(storage)
        e.registrations.submitGuest(guest())
        assertEquals(3, java.io.DataInputStream(java.io.ByteArrayInputStream(storage.bytes!!)).readInt())
        val restarted = backend(storage); login(restarted, password = "LegacyPass123!")
        assertTrue(restarted.auth.confirmEmail(challenge.id, token) is AuthResult.Success)
        assertEquals(GuestRegistrationOutcome.ExistingRegistration("guest@example.com"), restarted.registrations.submitGuest(guest()))
    }

    @Test fun corruptOrUnknownSnapshotIsNotOverwrittenByACommand() = runTest {
        val storage = Storage().apply { bytes = byteArrayOf(0, 0, 0, 99) }; val original = storage.bytes!!.copyOf()
        val e = backend(storage)
        assertEquals(SessionState.Unknown, e.auth.observeSession().first().state)
        assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.StorageUnavailable), e.registrations.submitGuest(guest()))
        assertArrayEquals(original, storage.bytes)
        e.scenarios.reset()
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        assertTrue(e.registrations.submitGuest(guest()) is GuestRegistrationOutcome.Issued)
    }

    @Test fun typedReadFailureAndExplicitRetryDoNotMasqueradeAsEmptyOrUnlimited() = runTest {
        val e = backend(Storage())
        e.scenarios.failNextRead(ProductReadError.TIMEOUT); e.events.refreshPublicEvent(FakeFixtures.OPEN)
        assertEquals(PublicEventResult.Failed(ProductReadError.TIMEOUT), e.events.observePublicEvent(FakeFixtures.OPEN).first())
        e.events.refreshPublicEvent(FakeFixtures.OPEN)
        assertTrue(e.events.observePublicEvent(FakeFixtures.OPEN).first() is PublicEventResult.Found)
        login(e); e.scenarios.failNextRead(ProductReadError.NETWORK); e.tickets.refreshMyTickets()
        assertEquals(MyTicketsResult.Failed(ProductReadError.NETWORK), e.tickets.observeMyTickets().first())
        e.tickets.refreshMyTickets()
        assertEquals(2, (e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.size)
    }

    @Test fun startedIsStillCurrentUntilEndButFinishedAndCancelledDoNotExposeQr() {
        val ticket = FakeFixtures.tickets(FakeFixtures.events()).first()
        val event = FakeFixtures.events().single { it.id == ticket.snapshot.eventId }
        assertTrue(ticket.isCurrent(event, event.startsAt.plusSeconds(1)))
        assertFalse(ticket.isCurrent(event, event.endsAt!!))
        assertFalse(ticket.canDisplayQr(event.copy(lifecycle = EventLifecycle.FINISHED), FakeFixtures.NOW))
        assertFalse(ticket.copy(status = TicketStatus.CANCELLED).canDisplayQr(event, FakeFixtures.NOW))
    }

    @Test fun cancelledUsedAndGrantChangesSurviveAnotherBackendWithoutPersistingSession() = runTest {
        val storage = Storage(); val e = backend(storage); login(e)
        val registered = e.registrations.submitAuthenticated(member()) as AuthenticatedRegistrationOutcome.Registered
        e.tickets.cancelOwnTicket(registered.ticketId)
        val used = TicketId("seed-ticket-alex-mixed")
        e.scenarios.setTicketStatus(used, TicketStatus.USED)
        e.scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, false))
        val restored = backend(storage)
        assertEquals(SessionState.Guest, restored.auth.observeSession().first().state)
        login(restored)
        val mine = restored.tickets.observeMyTickets().first() as MyTicketsResult.Ready
        assertEquals(TicketStatus.CANCELLED, mine.tickets.single { it.snapshot.ticketId == registered.ticketId }.status)
        assertEquals(TicketStatus.USED, mine.tickets.single { it.snapshot.ticketId == used }.status)
        assertEquals(AvailablePlaces.Limited(20), remaining(restored))
        assertFalse((restored.events.observePublicEvent(FakeFixtures.MIXED).first() as PublicEventResult.Found).access.canScan)
    }
}
