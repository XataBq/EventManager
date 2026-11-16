package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.RegistrationRepository
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class M03RegistrationStateTest {
    @Test fun guestRetryAfterRequiredQuestionAddedReplaysOriginalCommand() = schemaChangedRetry(authenticated = false, removeQuestion = false)
    @Test fun guestRetryAfterAnsweredQuestionRemovedReplaysOriginalCommand() = schemaChangedRetry(authenticated = false, removeQuestion = true)
    @Test fun authenticatedRetryAfterRequiredQuestionAddedReplaysOriginalCommand() = schemaChangedRetry(authenticated = true, removeQuestion = false)
    @Test fun authenticatedRetryAfterAnsweredQuestionRemovedReplaysOriginalCommand() = schemaChangedRetry(authenticated = true, removeQuestion = true)

    private fun schemaChangedRetry(authenticated: Boolean, removeQuestion: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        val original = e.events.observePublicEvent(PublicEventReference.Internal(EventId("open-registration"))).first() as PublicEventResult.Found
        val question = RegistrationQuestion.Text("original", "Исходный вопрос", true)
        val initial = original.copy(event = original.event.copy(questions = listOf(question)),
            contact = if (authenticated) RegistrationContact("retry@example.com", "Alex") else null)
        val stream = MutableStateFlow<PublicEventResult>(initial)
        val events = object : EventRepository by e.events {
            override fun observePublicEvent(reference: PublicEventReference) = stream
        }
        val guests = mutableListOf<GuestRegistrationCommand>()
        val members = mutableListOf<AuthenticatedRegistrationCommand>()
        // Model an accepted server operation whose first response was lost. Its replay returns the saved result.
        val issued = GuestTicketSnapshot(TicketId("accepted"), initial.event.id, initial.event.title,
            initial.event.startsAt, initial.event.zoneId, initial.event.venue, "Alex", "EVT-123456", "qr")
        val registrations = object : RegistrationRepository by e.registrations {
            override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome {
                guests += command
                return if (guests.size == 1) GuestRegistrationOutcome.Failed(RegistrationError.Timeout)
                    else GuestRegistrationOutcome.Issued(issued, command.email, accountCreated = true)
            }
            override suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand): AuthenticatedRegistrationOutcome {
                members += command
                return if (members.size == 1) AuthenticatedRegistrationOutcome.Failed(RegistrationError.Network)
                    else AuthenticatedRegistrationOutcome.Registered(issued.ticketId, existing = true)
            }
        }
        val saved = SavedStateHandle()
        val vm = EventViewModel(events, registrations, e.tickets, saved)
        try {
            vm.bind(initial.event.id.value); runCurrent()
            val form = RegistrationForm("Alex", "retry@example.com", answers = mapOf(question.id to Answer.Text("Original answer")), consent = true)
            vm.edit(form); vm.submit(); runCurrent()
            assertFalse(vm.state.value.submitting)
            assertEquals(if (authenticated) RegistrationError.Network else RegistrationError.Timeout, vm.state.value.error)
            val changed = if (removeQuestion) emptyList() else listOf(question, RegistrationQuestion.Text("new", "Новый обязательный вопрос", true))
            stream.value = initial.copy(event = initial.event.copy(questions = changed)); runCurrent()
            val originalCommand = if (authenticated) members.single().let {
                GuestRegistrationCommand(it.eventId, it.localSchemaRevision, it.name, initial.contact!!.email,
                    it.phone, it.answers, it.consentAccepted, it.operationId)
            } else guests.single()
            assertTrue(RegistrationValidator.validate(originalCommand, changed, initial.policy) is RegistrationValidation.Invalid)
            val keptForm = vm.state.value.form
            vm.edit(keptForm.copy(name = "Changed"))
            assertEquals(keptForm, vm.state.value.form)
            vm.submit(); runCurrent()
            if (authenticated) {
                assertEquals(2, members.size); assertEquals(members[0], members[1])
                assertEquals(issued.ticketId.value, (vm.state.value.action as EventAction.Ticket).ticketId)
                assertTrue(guests.isEmpty())
            } else {
                assertEquals(2, guests.size); assertEquals(guests[0], guests[1])
                assertEquals(issued, vm.state.value.receipt!!.ticket)
                assertTrue(members.isEmpty())
            }
            assertFalse(vm.state.value.submitting)
            assertNull(saved.get<Boolean>("registrationPending"))
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun uncertainRetryKeepsKeyBodyAndBlocksEditingUntilResolved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        val sent = mutableListOf<GuestRegistrationCommand>()
        val repo = object : RegistrationRepository by e.registrations {
            override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome {
                sent += command
                return if(sent.size == 1) GuestRegistrationOutcome.Failed(RegistrationError.Timeout)
                    else e.registrations.submitGuest(command)
            }
        }
        val saved = SavedStateHandle(); val vm = EventViewModel(e.events,repo,e.tickets,saved)
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }
            val form = RegistrationForm("Alex","retry@example.com",consent = true)
            vm.edit(form); vm.submit(); vm.state.first { it.error == RegistrationError.Timeout && !it.submitting }
            assertEquals(setOf("registrationPending"),saved.keys())
            vm.edit(form.copy(name="Changed")); assertEquals(form,vm.state.value.form)
            vm.submit(); vm.state.first { it.receipt != null }
            assertEquals(2,sent.size); assertEquals(sent[0],sent[1]); assertTrue(vm.state.value.receipt!!.accountCreated)
            assertNull(saved.get<Boolean>("registrationPending"))
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun restoredUnknownOperationDoesNotSubmitAgainWithoutBody() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        var calls = 0
        val repo = object : RegistrationRepository by e.registrations {
            override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome { calls++; error("Unexpected resend") }
        }
        val vm = EventViewModel(e.events,repo,e.tickets,SavedStateHandle(mapOf("registrationPending" to true)))
        try {
            vm.bind("open-registration"); vm.state.first { it.data != null }; vm.submit(); runCurrent()
            assertEquals(RegistrationError.RecoveryRequired,vm.state.value.error); assertEquals(0,calls)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun guestCapabilityAndServerManualCodeBelongOnlyToReceiptState() {
        val saved = SavedStateHandle(); val state = GuestReceiptState(saved)
        val ticket = GuestTicketSnapshot(ru.eventpass.core.domain.event.TicketId("ticket"),
            ru.eventpass.core.domain.event.EventId("event"), "Event", java.time.Instant.EPOCH,
            "Europe/Moscow", "Hall", "Alex", "EVT-123456", "qr", guestAccess="capability",
            manualCode=ru.eventpass.core.domain.checkin.ManualCode("EVT-123456"))
        state.write(ticket,"guest@example.com",true)
        assertEquals(ticket,state.read()!!.ticket); assertTrue(state.read()!!.accountCreated)
        state.clear(); assertNull(state.read()); assertTrue(saved.keys().isEmpty())
    }
}
