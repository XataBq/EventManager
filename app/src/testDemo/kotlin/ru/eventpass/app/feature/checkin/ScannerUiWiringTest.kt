package ru.eventpass.app.feature.checkin

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.fake.product.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerUiWiringTest {
    @Test fun manualWithoutCameraClearsCredentialAndBackDoesNotExitBeforeResultIsCleared() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        e.scenarios.signInAs(FakeFixtures.ALEX)
        val vm = ScannerViewModel(e.checkIns)
        try {
            vm.bind(FakeFixtures.MIXED.value); vm.foreground(true); vm.state.first { it.access != null }
            vm.permission(ScannerPermission.DENIED)
            val code = ManualCodePolicy.fromQr("demo-token-alex-mixed").value
            vm.edit(code); vm.submit(); vm.submit()
            assertEquals("", vm.manualInput.value)
            assertTrue(vm.state.first { it.phase == ScannerPhase.RESULT }.result is CheckInOutcome.Admitted)
            var closed = false; vm.back { closed = true }
            assertFalse(closed); assertEquals(ScannerPhase.READY, vm.state.value.phase)
            vm.edit(code); vm.submit()
            assertTrue(vm.state.first { it.phase == ScannerPhase.RESULT }.result is CheckInOutcome.AlreadyUsed)
            vm.foreground(false)
            assertNull(vm.state.value.result); assertFalse(vm.state.value.cameraActive)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun revokeClearsResultAndCameraFailureLeavesManualAvailable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
        e.scenarios.signInAs(FakeFixtures.ALEX); val vm = ScannerViewModel(e.checkIns)
        try {
            vm.bind(FakeFixtures.MIXED.value); vm.foreground(true); vm.state.first { it.access != null }
            vm.permission(ScannerPermission.GRANTED); vm.camera(); assertTrue(vm.state.value.cameraActive)
            vm.cameraFailure(); assertFalse(vm.state.value.cameraActive); assertTrue(vm.cameraFailed.value)
            vm.edit(ManualCodePolicy.fromQr("demo-token-alex-mixed").value); vm.submit()
            vm.state.first { it.phase == ScannerPhase.RESULT }
            e.scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, false))
            val denied = vm.state.first { it.phase == ScannerPhase.ACCESS_DENIED }
            assertNull(denied.result); assertNull(denied.access)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
