package ru.eventpass.data.fake.product

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.AvailablePlaces
import ru.eventpass.core.domain.event.EventLifecycle
import ru.eventpass.core.domain.event.PublicEventResult
import ru.eventpass.core.domain.event.RegistrationAvailability
import ru.eventpass.core.domain.event.RegistrationClosureReason
import ru.eventpass.core.domain.event.ScanGrant
import ru.eventpass.core.domain.event.UserId
import ru.eventpass.core.domain.registration.Answer
import ru.eventpass.core.domain.registration.FieldRequirement
import ru.eventpass.core.domain.registration.GuestRegistrationCommand
import ru.eventpass.core.domain.registration.GuestRegistrationOutcome
import ru.eventpass.core.domain.registration.MyTicketsResult
import ru.eventpass.core.domain.registration.RegistrationError
import ru.eventpass.core.domain.registration.RegistrationPolicy

class FakeEventPassEnvironmentTest {
    private fun command(email: String = "new@example.com") = GuestRegistrationCommand(
        eventId = FakeFixtures.OPEN, formVersion = 1, name = "李", email = email,
        consentAccepted = true,
    )

    @Test fun guestTicketUpdatesPublicAvailabilityWithoutCreatingAnAccount() = runTest {
        val environment = FakeEventPassEnvironment()
        val updates = async(start = CoroutineStart.UNDISPATCHED) {
            environment.events.observePublicEvent(FakeFixtures.OPEN).drop(1).first()
        }
        val accountsBefore = environment.scenarios.accounts()
        val issued = environment.registrations.submitGuest(command()) as GuestRegistrationOutcome.Issued
        assertEquals(accountsBefore, environment.scenarios.accounts())
        assertNull(environment.scenarios.registrationFor(issued.ticket.ticketId)?.userId)
        assertEquals(listOf(FakeTicketEmail("new@example.com", issued.ticket.ticketId)), environment.scenarios.outbox())
        assertFalse(issued.ticket.qrPayload.contains("new@example.com"))
        assertFalse(issued.ticket.qrPayload.contains("李"))
        assertEquals(MyTicketsResult.AuthenticationRequired, environment.tickets.observeMyTickets().first())
        val event = updates.await() as PublicEventResult.Found
        assertEquals(RegistrationAvailability.Open(AvailablePlaces.Limited(19)), event.availability)
    }

    @Test fun separateAccountOnSameEmailSeesEarlierTicketsOnlyAfterVerifiedSignIn() = runTest {
        val environment = FakeEventPassEnvironment()
        val issued = environment.registrations.submitGuest(command()) as GuestRegistrationOutcome.Issued
        val newAccount = Account(UserId("new-account"), "new@example.com", false)
        environment.scenarios.putAccount(newAccount)
        assertEquals(MyTicketsResult.AuthenticationRequired, environment.tickets.observeMyTickets().first())
        environment.scenarios.signInAs(newAccount.id)
        assertEquals(MyTicketsResult.EmailVerificationRequired, environment.tickets.observeMyTickets().first())
        environment.scenarios.signOut()
        environment.scenarios.putAccount(newAccount.copy(emailVerified = true))
        // Confirmation changes the account, but it does not authenticate the client.
        assertEquals(MyTicketsResult.AuthenticationRequired, environment.tickets.observeMyTickets().first())
        environment.scenarios.signInAs(newAccount.id)
        val mine = environment.tickets.observeMyTickets().first() as MyTicketsResult.Ready
        assertEquals(listOf(issued.ticket), mine.tickets.map { it.snapshot })
        environment.scenarios.signInAs(FakeFixtures.ALEX)
        val other = environment.tickets.observeMyTickets().first() as MyTicketsResult.Ready
        assertFalse(other.tickets.any { it.snapshot.ticketId == issued.ticket.ticketId })
        environment.scenarios.signOut()
        assertEquals(MyTicketsResult.AuthenticationRequired, environment.tickets.observeMyTickets().first())
    }

    @Test fun duplicateGuestSubmissionDoesNotRecoverQrOrCreateAnotherTicket() = runTest {
        val environment = FakeEventPassEnvironment()
        environment.registrations.submitGuest(command()) as GuestRegistrationOutcome.Issued
        val duplicate = environment.registrations.submitGuest(command())
        assertEquals(GuestRegistrationOutcome.ExistingRegistration("new@example.com"), duplicate)
        assertEquals(1, environment.scenarios.outbox().size)
        val event = environment.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        assertEquals(RegistrationAvailability.Open(AvailablePlaces.Limited(19)), event.availability)
    }

    @Test fun existingEmailRequiresLoginWithoutSavingAnswersOrIssuingTicket() = runTest {
        val environment = FakeEventPassEnvironment()
        val result = environment.registrations.submitGuest(command("alex@example.com"))
        assertEquals(GuestRegistrationOutcome.RequiresLogin("alex@example.com"), result)
        assertTrue(environment.scenarios.outbox().isEmpty())
        environment.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(2, (environment.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.size)
        val event = environment.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        assertEquals(RegistrationAvailability.Open(AvailablePlaces.Limited(20)), event.availability)
    }

    @Test fun consentAndStaleFormFailuresHaveNoSideEffects() = runTest {
        val environment = FakeEventPassEnvironment()
        val invalid = environment.registrations.submitGuest(command().copy(consentAccepted = false))
        assertTrue((invalid as GuestRegistrationOutcome.Failed).error is RegistrationError.Validation)
        assertEquals(
            GuestRegistrationOutcome.Failed(RegistrationError.StaleForm),
            environment.registrations.submitGuest(command().copy(formVersion = 2)),
        )
        assertTrue(environment.scenarios.outbox().isEmpty())
    }

    @Test fun fullClosedAndMissingEventsReturnDistinctResults() = runTest {
        val environment = FakeEventPassEnvironment()
        val event = FakeFixtures.events().single { it.id == FakeFixtures.OPEN }
        environment.scenarios.putEvent(event.copy(capacity = 0))
        assertEquals(
            GuestRegistrationOutcome.Failed(RegistrationError.Unavailable(RegistrationClosureReason.FULL)),
            environment.registrations.submitGuest(command()),
        )
        environment.scenarios.putEvent(event.copy(registrationOpen = false))
        assertEquals(
            GuestRegistrationOutcome.Failed(RegistrationError.Unavailable(RegistrationClosureReason.CLOSED)),
            environment.registrations.submitGuest(command()),
        )
        environment.scenarios.putEvent(event.copy(lifecycle = EventLifecycle.DRAFT))
        assertEquals(PublicEventResult.NotFound, environment.events.observePublicEvent(event.id).first())
        assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.NotFound), environment.registrations.submitGuest(command()))
        assertTrue(environment.scenarios.outbox().isEmpty())
    }

    @Test fun submissionChecksTheClockEvenWhenAnOldPageSaidOpen() = runTest {
        val clock = MutableClock(FakeFixtures.NOW)
        val environment = FakeEventPassEnvironment(clock)
        val event = FakeFixtures.events().single { it.id == FakeFixtures.OPEN }
        assertTrue((environment.events.observePublicEvent(event.id).first() as PublicEventResult.Found)
            .availability is RegistrationAvailability.Open)
        clock.now = event.startsAt
        assertEquals(
            GuestRegistrationOutcome.Failed(RegistrationError.Unavailable(RegistrationClosureReason.STARTED)),
            environment.registrations.submitGuest(command()),
        )
        environment.events.refreshPublicEvent(event.id)
        assertEquals(
            RegistrationAvailability.Closed(RegistrationClosureReason.STARTED),
            (environment.events.observePublicEvent(event.id).first() as PublicEventResult.Found).availability,
        )
    }

    @Test fun failureIsControlledAndDoesNotSilentlyRetryOrWriteAnOutboxItem() = runTest {
        val environment = FakeEventPassEnvironment()
        environment.scenarios.failNextGuestSubmission(RegistrationError.Timeout)
        assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.Timeout), environment.registrations.submitGuest(command()))
        assertTrue(environment.scenarios.outbox().isEmpty())
        assertTrue(environment.registrations.submitGuest(command()) is GuestRegistrationOutcome.Issued)
    }

    @Test fun policyChangesDoNotRewriteExistingRegistrationAndAnswersAreCopied() = runTest {
        val environment = FakeEventPassEnvironment()
        val answers = mutableMapOf<String, Answer>("organization" to Answer.Text(" Original "))
        val issued = environment.registrations.submitGuest(command().copy(answers = answers)) as GuestRegistrationOutcome.Issued
        answers["organization"] = Answer.Text("Changed later")
        environment.scenarios.setPolicy(RegistrationPolicy(FieldRequirement.REQUIRED))
        val failure = environment.registrations.submitGuest(command("another@example.com"))
        assertTrue((failure as GuestRegistrationOutcome.Failed).error is RegistrationError.Validation)
        val original = environment.scenarios.registrationFor(issued.ticket.ticketId)!!
        assertNull(original.phone)
        assertEquals(Answer.Text("Original"), original.answers["organization"])
        environment.scenarios.setPolicy(RegistrationPolicy(FieldRequirement.OPTIONAL))
        assertTrue(environment.registrations.submitGuest(command("another@example.com")) is GuestRegistrationOutcome.Issued)
    }

    @Test fun mixedMembershipSurvivesRevokingOnlyThatEventsGrant() = runTest {
        val environment = FakeEventPassEnvironment()
        environment.scenarios.signInAs(FakeFixtures.ALEX)
        suspend fun access(eventId: ru.eventpass.core.domain.event.EventId) =
            (environment.events.observePublicEvent(eventId).first() as PublicEventResult.Found).access
        assertTrue(access(FakeFixtures.OWNED).canManage)
        assertTrue(access(FakeFixtures.VOLUNTEER).canScan)
        assertFalse(access(FakeFixtures.ATTENDING).canScan)
        assertTrue(access(FakeFixtures.MIXED).canScan)
        environment.scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, active = false))
        assertFalse(access(FakeFixtures.MIXED).canScan)
        assertTrue(access(FakeFixtures.MIXED).canViewOwnTicket)
        assertTrue(access(FakeFixtures.VOLUNTEER).canScan)
        assertTrue(access(FakeFixtures.OWNED).canManage)
    }

    @Test fun resetRestoresSeedAndRemovesSessionAndNewSubmissions() = runTest {
        val environment = FakeEventPassEnvironment()
        environment.registrations.submitGuest(command())
        environment.scenarios.signInAs(FakeFixtures.ALEX)
        environment.scenarios.reset()
        assertEquals("t02-v1", environment.scenarios.seed)
        assertTrue(environment.scenarios.outbox().isEmpty())
        assertEquals(MyTicketsResult.AuthenticationRequired, environment.tickets.observeMyTickets().first())
        assertTrue(environment.registrations.submitGuest(command()) is GuestRegistrationOutcome.Issued)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
}
