package ru.eventpass.app.feature.profile

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.event.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val profile=Profile(Account(UserId("alex"),"alex@example.com",true),ProfileFields("Alex"))
    private inner class Repo : ProfileRepository {
        val values=MutableStateFlow<ProfileResult>(ProfileResult.Ready(profile))
        var save: suspend(ProfileFields)->ProfileResult={ProfileResult.Ready(profile.copy(fields=it))}
        override fun observeProfile()=values
        override suspend fun saveProfile(fields: ProfileFields)=save(fields)
    }
    @Test fun observerDoesNotEraseDirtyFormAndBackCanDiscardOrSave()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(); val vm=ProfileViewModel(repo)
        try {
            vm.start(); runCurrent(); vm.edit(ProfileFields("Ж")); repo.values.value=ProfileResult.Ready(profile.copy(fields=ProfileFields("Server"))); runCurrent()
            assertEquals("Ж",vm.state.value.fields.name); assertTrue(vm.state.value.dirty)
            assertFalse(vm.requestLeave()); vm.keepEditing(); assertFalse(vm.state.value.confirming)
            vm.requestLeave(); vm.save(true); runCurrent(); assertTrue(vm.state.value.exit); assertFalse(vm.state.value.dirty); assertTrue(vm.state.value.saved)
            vm.consumeExit(); vm.edit(ProfileFields("Other")); vm.requestLeave(); vm.discard()
            assertEquals("Ж",vm.state.value.fields.name); assertTrue(vm.state.value.exit)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun lateSaveResultFinallyCannotAffectNewOwnerAndDoubleTapIsSingleFlight()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(); val pending=mutableListOf<Continuation<ProfileResult>>()
        repo.save={suspendCoroutine {pending+=it}}; val vm=ProfileViewModel(repo)
        try {
            vm.start();runCurrent();vm.edit(ProfileFields("Old"));vm.save();vm.save();runCurrent();assertEquals(1,pending.size)
            vm.stop();vm.start();runCurrent();vm.edit(ProfileFields("New"));vm.save();runCurrent()
            pending[0].resume(ProfileResult.Ready(profile.copy(fields=ProfileFields("Old"))));runCurrent()
            assertTrue(vm.state.value.busy);assertEquals("New",vm.state.value.fields.name)
            pending[1].resume(ProfileResult.Failed(ProfileError.TIMEOUT));runCurrent()
            assertFalse(vm.state.value.busy);assertTrue(vm.state.value.dirty);assertEquals(ProfileError.TIMEOUT,vm.state.value.error)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Test fun hiddenOrDeniedClearsPiiAndSaveFailureStaysInConfirmation()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val repo=Repo();val vm=ProfileViewModel(repo)
        try {
            vm.start();runCurrent();vm.edit(ProfileFields("Modified"));vm.requestLeave()
            repo.save={ProfileResult.Failed(ProfileError.NETWORK)};vm.save(true);runCurrent()
            assertTrue(vm.state.value.confirming);assertFalse(vm.state.value.exit);assertEquals(ProfileError.NETWORK,vm.state.value.error)
            repo.values.value=ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED);runCurrent()
            assertNull(vm.state.value.profile);assertEquals(ProfileFields(),vm.state.value.fields);assertFalse(vm.state.value.confirming)
            vm.start();vm.stop();runCurrent();assertNull(vm.state.value.profile)
        } finally {vm.viewModelScope.cancel();Dispatchers.resetMain()}
    }
}
