package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.RegistrationRepository
import ru.eventpass.core.domain.registration.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class M03RegistrationStateTest {
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
