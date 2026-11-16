package ru.eventpass.data.fake.product

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.registration.*

class M03GuestRegistrationTest {
    @Test fun guestReplayDoesNotCreateSecondAccountTicketMailOrSessionAndSetupRequiresLogin() = runTest {
        val e=FakeEventPassEnvironment(authPolicy=FakeAuthPolicy(passwordIterations=1000))
        val command=GuestRegistrationCommand(FakeFixtures.OPEN,1,"Alex","replay@example.com",consentAccepted=true)
        val first=e.registrations.submitGuest(command) as GuestRegistrationOutcome.Issued
        assertEquals(first,e.registrations.submitGuest(command))
        assertEquals(3,e.scenarios.accounts().size); assertEquals(1,e.scenarios.outbox().size)
        assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.RecoveryRequired),e.registrations.submitGuest(command.copy(name="Changed")))
        assertFalse(e.auth.currentSession().state is SessionState.Authenticated)
        assertTrue(first.ticket.displayNumber.startsWith("EVT-")); assertEquals(first.ticket.displayNumber,first.ticket.manualCode.value)
        assertEquals(AuthResult.Failed(AuthError.InvalidCredentials),e.auth.signIn(command.email,AuthSecret("DemoPass123!")))
        val password=AuthSecret("GuestPassword123!")
        assertTrue(e.auth.completeEmailLink(e.scenarios.authOutbox().last().secret,password) is AuthResult.Success)
        assertFalse(e.auth.currentSession().state is SessionState.Authenticated)
        assertTrue(e.auth.signIn(command.email,password) is AuthResult.Success)
        assertEquals(first.ticket.ticketId,(e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.single().snapshot.ticketId)
    }
}
