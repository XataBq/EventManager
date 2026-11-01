package ru.eventpass.app.feature.volunteers

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.fake.product.*
import kotlin.coroutines.*

@OptIn(ExperimentalCoroutinesApi::class)
class VolunteersViewModelTest {
    @Test fun networkFailureStaysWithRevokeConfirmationAndCanRetry() = revokeFailure(VolunteerError.NETWORK)
    @Test fun timeoutStaysWithRevokeConfirmationAndCanRetry() = revokeFailure(VolunteerError.TIMEOUT)
    @Test fun storageFailureStaysWithRevokeConfirmationAndCanRetry() = revokeFailure(VolunteerError.STORAGE_UNAVAILABLE)

    private fun revokeFailure(error: VolunteerError) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        e.scenarios.signInAs(FakeFixtures.ALEX)
        e.volunteers.grant(FakeFixtures.OWNED, "host@example.com")
        val vm = VolunteersViewModel(e.volunteers)
        try {
            vm.start(FakeFixtures.OWNED.value)
            val volunteer = vm.state.first { !it.loading }.volunteers.single()
            vm.confirm(volunteer); e.scenarios.failNextVolunteer(error); vm.revoke()
            val failed = vm.state.first { it.revokeError == error && !it.busy }
            assertEquals(volunteer, failed.revoke); assertEquals(listOf(volunteer), failed.volunteers)
            assertNull(failed.error) // The failure belongs to the modal command, not the list/reload UI.
            vm.confirm(null); assertNull(vm.state.value.revokeError)
            vm.confirm(volunteer); assertNull(vm.state.value.revokeError)
            vm.revoke()
            vm.state.first { it.revoke == null && !it.busy }
            assertNull(vm.state.value.revokeError)
            assertTrue((e.volunteers.observeVolunteers(FakeFixtures.OWNED).first() as VolunteersResult.Ready).volunteers.isEmpty())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun unknownAccountAndConfirmedRevokeUseOneSharedList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        e.scenarios.signInAs(FakeFixtures.ALEX); val vm = VolunteersViewModel(e.volunteers)
        try {
            vm.start(FakeFixtures.OWNED.value); vm.state.first { !it.loading }
            vm.edit("unknown@example.com"); vm.grant()
            vm.state.first { it.error == VolunteerError.ACCOUNT_NOT_FOUND && !it.busy }
            vm.edit("host@example.com"); vm.grant(); vm.grant()
            val volunteer = vm.state.first { it.volunteers.size == 1 && !it.busy }.volunteers.single()
            assertEquals("", vm.state.value.input)
            vm.confirm(volunteer); assertEquals(volunteer, vm.state.value.revoke)
            vm.revoke(); vm.state.first { it.volunteers.isEmpty() && !it.busy }
            assertNull(vm.state.value.revoke)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun hiddenEntryDiscardsInputAndLateCommandCannotUnlockNewEntry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = mutableListOf<Continuation<VolunteerOutcome>>()
        val repo = object : VolunteerRepository {
            override fun observeVolunteers(eventId: EventId) = flowOf(VolunteersResult.Ready(emptyList()))
            override suspend fun grant(eventId: EventId, emailOrId: String): VolunteerOutcome = suspendCoroutine { pending += it }
            override suspend fun revoke(eventId: EventId, userId: UserId): VolunteerOutcome = error("not expected")
        }
        val vm = VolunteersViewModel(repo)
        try {
            vm.start("one"); runCurrent(); vm.edit("old"); vm.grant(); runCurrent()
            vm.stop(); assertEquals("", vm.state.value.input)
            vm.start("two"); runCurrent(); vm.edit("new"); vm.grant(); runCurrent()
            pending[0].resume(VolunteerOutcome.Failed(VolunteerError.NETWORK)); runCurrent()
            assertTrue(vm.state.value.busy); assertNull(vm.state.value.error)
            pending[1].resume(VolunteerOutcome.Failed(VolunteerError.TIMEOUT)); runCurrent()
            assertFalse(vm.state.value.busy); assertEquals(VolunteerError.TIMEOUT, vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun deniedObservationRemovesPrivateListAndConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val list = MutableStateFlow<VolunteersResult>(VolunteersResult.Ready(listOf(Volunteer(ScanGrant(EventId("event"), UserId("user")), "user@example.com"))))
        val repo = object : VolunteerRepository {
            override fun observeVolunteers(eventId: EventId) = list
            override suspend fun grant(eventId: EventId, emailOrId: String): VolunteerOutcome = error("not expected")
            override suspend fun revoke(eventId: EventId, userId: UserId): VolunteerOutcome = error("not expected")
        }
        val vm = VolunteersViewModel(repo)
        try {
            vm.start("event"); runCurrent(); vm.confirm(vm.state.value.volunteers.single())
            list.value = VolunteersResult.Failed(VolunteerError.FORBIDDEN); runCurrent()
            assertTrue(vm.state.value.denied); assertTrue(vm.state.value.volunteers.isEmpty()); assertNull(vm.state.value.revoke)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
