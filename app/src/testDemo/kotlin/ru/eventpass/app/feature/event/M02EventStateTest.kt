package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class M02EventStateTest {
    private fun env() = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))

    @Test fun serverTicketIdOpensWithoutWaitingForFutureTicketRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = env()
        val found = e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        val events = object : EventRepository by e.events {
            override fun observePublicEvent(reference: PublicEventReference) = flowOf(found.copy(ownTicketId = TicketId("server-ticket")))
        }
        val tickets = object : TicketRepository by e.tickets {
            override fun observeMyTickets(): Flow<MyTicketsResult> = error("Public viewer must not await M03")
        }
        val vm = EventViewModel(events, e.registrations, tickets, SavedStateHandle())
        try {
            vm.bind("open-registration"); runCurrent(); vm.openOwnTicket(); runCurrent()
            assertEquals("server-ticket", (vm.state.value.action as EventAction.Ticket).ticketId)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun actorLossClearsFormAndSuppressesAlreadyPendingAuthenticatedReply() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = env(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val found = e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        val values = MutableStateFlow<PublicEventResult>(found)
        val events = object : EventRepository by e.events { override fun observePublicEvent(reference: PublicEventReference) = values }
        val pending = CompletableDeferred<Continuation<AuthenticatedRegistrationOutcome>>()
        val registrations = object : RegistrationRepository by e.registrations {
            override suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand): AuthenticatedRegistrationOutcome = suspendCoroutine { pending.complete(it) }
        }
        val vm = EventViewModel(events, registrations, e.tickets, SavedStateHandle())
        try {
            vm.bind("open-registration"); runCurrent()
            vm.edit(RegistrationForm("Private", "alex@example.com", "+79991234567", consent = true)); vm.submit()
            val reply = pending.await()
            values.value = found.copy(contact = null, ownTicketId = null); runCurrent()
            assertEquals(RegistrationForm(), vm.state.value.form); assertFalse(vm.state.value.submitting)
            reply.resume(AuthenticatedRegistrationOutcome.Registered(TicketId("old-actor-ticket"), false)); runCurrent()
            assertNull(vm.state.value.action); assertNull(vm.state.value.receipt); assertNull(vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun forbiddenOrDeletedPublicResourceClearsPreviouslyEnteredPii() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val e = env()
        val found = e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found
        val values = MutableStateFlow<PublicEventResult>(found)
        val events = object : EventRepository by e.events { override fun observePublicEvent(reference: PublicEventReference) = values }
        val vm = EventViewModel(events, e.registrations, e.tickets, SavedStateHandle())
        try {
            vm.bind("open-registration"); runCurrent(); vm.edit(RegistrationForm("Private", "guest@example.com", consent = true))
            values.value = PublicEventResult.Failed(ProductReadError.NETWORK); runCurrent()
            assertEquals("Private", vm.state.value.form.name)
            values.value = PublicEventResult.Failed(ProductReadError.FORBIDDEN); runCurrent()
            assertNull(vm.state.value.data); assertEquals(RegistrationForm(), vm.state.value.form)
            values.value = found; runCurrent(); vm.edit(RegistrationForm("Private", "guest@example.com"))
            values.value = PublicEventResult.NotFound; runCurrent()
            assertTrue(vm.state.value.notFound); assertNull(vm.state.value.data); assertEquals(RegistrationForm(), vm.state.value.form)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
