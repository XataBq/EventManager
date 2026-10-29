package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.RegistrationRepository
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class EventViewModelTest {
    private fun form(email: String = "flow@example.com") = RegistrationForm("李", email, consent = true)
    private fun environment() = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
    @Test fun receiptIsSavedBeforePublicationAndClosingItDoesNotRestoreFromRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = environment(); val saved = SavedStateHandle()
        val vm = EventViewModel(e.events, e.registrations, e.tickets, saved)
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }
            vm.edit(form()); vm.submit()
            val issued = vm.state.first { it.receipt != null }.receipt!!
            assertEquals(issued, GuestReceiptState(saved).read())
            assertEquals(setOf("guestReceipt"), saved.keys())
            assertEquals(RegistrationForm(), vm.state.value.form)
            val restored = GuestReceiptState(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
            assertEquals(issued, restored.read())
            vm.close()
            assertNull(GuestReceiptState(saved).read())
            assertEquals(GuestRegistrationOutcome.ExistingRegistration("flow@example.com"), e.registrations.submitGuest(
                GuestRegistrationCommand(ru.eventpass.core.domain.event.EventId("open-registration"), 1, "李", "flow@example.com", consentAccepted = true)))
            assertNull(vm.state.value.receipt)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun existingAccountClearsAnswersAndDoesNotAutomaticallySubmitAfterLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = environment(); val saved = SavedStateHandle(); val vm = EventViewModel(e.events, e.registrations, e.tickets, saved)
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }
            vm.edit(form("alex@example.com").copy(answers = mapOf("organization" to Answer.Text("Discard me"))))
            vm.submit()
            val action = vm.state.first { it.action is EventAction.Login }.action as EventAction.Login
            assertEquals("alex@example.com", action.email)
            assertEquals(RegistrationForm(), vm.state.value.form)
            assertTrue(saved.keys().isEmpty())
            e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!"))
            vm.state.first { it.data?.contact != null }
            assertTrue(e.scenarios.outbox().isEmpty()); assertNull(vm.state.value.receipt)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun duplicatePressAndLeavingFormCannotPublishAnOldResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = environment(); val waiting = CompletableDeferred<Unit>(); var calls = 0
        val repository = object : RegistrationRepository by e.registrations {
            override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome {
                calls++; waiting.await(); return e.registrations.submitGuest(command)
            }
        }
        val vm = EventViewModel(e.events, repository, e.tickets, SavedStateHandle())
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }; vm.edit(form())
            vm.submit(); vm.submit(); runCurrent(); assertEquals(1, calls)
            vm.leaveForm(); waiting.complete(Unit); runCurrent()
            assertFalse(vm.state.value.submitting); assertNull(vm.state.value.receipt)
            assertTrue(e.scenarios.outbox().isEmpty())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun timeoutNeedsAnExplicitRetryAndStaleFormDropsAnswers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = environment(); val vm = EventViewModel(e.events, e.registrations, e.tickets, SavedStateHandle())
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }; vm.edit(form())
            e.scenarios.failNextGuestSubmission(RegistrationError.Timeout); vm.submit()
            vm.state.first { it.error == RegistrationError.Timeout && !it.submitting }
            assertTrue(e.scenarios.outbox().isEmpty())
            e.scenarios.failNextGuestSubmission(RegistrationError.StaleForm); vm.submit()
            vm.state.first { it.error == RegistrationError.StaleForm && !it.submitting }
            assertEquals(RegistrationForm(), vm.state.value.form)
            assertTrue(e.scenarios.outbox().isEmpty())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun readingOwnTicketKeepsNetworkFailureDistinctFromLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = environment()
        val tickets = object : ru.eventpass.core.domain.TicketRepository by e.tickets {
            override fun observeMyTickets() = kotlinx.coroutines.flow.flowOf(MyTicketsResult.Failed(ru.eventpass.core.domain.event.ProductReadError.NETWORK))
        }
        val vm = EventViewModel(e.events, e.registrations, tickets, SavedStateHandle())
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }
            vm.openOwnTicket(); runCurrent()
            assertEquals(RegistrationError.Network, vm.state.value.error)
            assertNull(vm.state.value.action)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
