package ru.eventpass.core.domain.checkin

import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.event.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerControllerTest {
    private val id = EventId("event")
    private val allowed = ScannerAccess.Allowed(id, "Событие", ScannerScope(UserId("checker"), 1))
    private val code = ManualCodePolicy.fromQr("opaque-test-token").value
    private val record = CheckInRecord("first", id, TicketId("ticket"), UserId("checker"), Instant.EPOCH, "Участник", "DEMO")
    private inner class Repository : CheckInRepository {
        val access = MutableStateFlow<ScannerAccess>(allowed)
        val calls = mutableListOf<Continuation<CheckInOutcome>>()
        val credentials = mutableListOf<TicketCredential>()
        override fun observeAccess(eventId: EventId) = access
        override suspend fun refreshAccess(eventId: EventId) = Unit
        override suspend fun checkIn(eventId: EventId, credential: TicketCredential): CheckInOutcome = suspendCoroutine {
            credentials += credential; calls += it
        }
    }
    @Test fun permissionDeniedAndPermanentDeniedKeepManualInputUsable() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.requestCamera()
        assertEquals(ScannerPhase.PERMISSION_REQUIRED, model.state.value.phase)
        model.permission(ScannerPermission.PERMANENTLY_DENIED)
        assertFalse(model.state.value.cameraActive); model.manual(code); runCurrent()
        assertEquals(1, repo.calls.size); assertEquals(ScannerPhase.SUBMITTING, model.state.value.phase)
        repo.calls.single().resume(CheckInOutcome.Admitted(record)); runCurrent()
        assertEquals(ScannerPhase.RESULT, model.state.value.phase)
        model.close()
    }
    @Test fun framesAreSingleFlightAndResultRequiresExplicitNext() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.requestCamera(); model.permission(ScannerPermission.GRANTED)
        val cycle = model.state.value.generation
        model.frame(cycle, emptyList()); assertEquals(ScannerPhase.SCANNING, model.state.value.phase)
        model.frame(cycle, listOf("z-token", "a-token")); model.frame(cycle, listOf("another")); model.manual(code); runCurrent()
        assertEquals(listOf(TicketCredential.Qr(QrToken("a-token"))), repo.credentials)
        assertFalse(model.state.value.cameraActive)
        repo.calls[0].resume(CheckInOutcome.Admitted(record)); runCurrent()
        model.frame(cycle, listOf("old")); runCurrent(); assertEquals(1, repo.calls.size)
        model.next(); assertTrue(model.state.value.cameraActive); assertNull(model.state.value.result)
        model.frame(cycle, listOf("stale-camera")); runCurrent(); assertEquals(1, repo.calls.size)
        model.frame(model.state.value.generation, listOf("current")); runCurrent(); assertEquals(2, repo.calls.size)
        repo.calls[1].resume(CheckInOutcome.AlreadyUsed(record)); runCurrent()
        model.backFromResult(); assertEquals(ScannerPhase.READY, model.state.value.phase); assertFalse(model.state.value.cameraActive)
        model.close()
    }
    @Test fun networkAndTimeoutAreNotTicketResultsAndDoNotRetryAutomatically() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true)
        for (error in listOf(CheckInError.NETWORK, CheckInError.TIMEOUT, CheckInError.STORAGE_UNAVAILABLE)) {
            model.manual(code); runCurrent()
            repo.calls.last().resume(CheckInOutcome.Failed(error)); runCurrent()
            assertEquals(ScannerPhase.ERROR, model.state.value.phase); assertEquals(error, model.state.value.error)
            assertNull(model.state.value.result); val count = repo.calls.size
            runCurrent(); assertEquals(count, repo.calls.size)
        }
        model.close()
    }
    @Test fun revokedAccessClearsPiiAndLateResponseCannotRestoreIt() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.manual(code); runCurrent()
        repo.access.value = ScannerAccess.Denied(CheckInError.FORBIDDEN); runCurrent()
        assertNull(model.state.value.access); assertNull(model.state.value.result); assertFalse(model.state.value.cameraActive)
        repo.calls[0].resume(CheckInOutcome.Admitted(record)); runCurrent()
        assertEquals(ScannerPhase.ACCESS_DENIED, model.state.value.phase); assertNull(model.state.value.result)
        model.close()
    }
    @Test fun oldCancelledFinallyCannotUnlockNewScanOfSameEventUnderNewScope() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.manual(code); runCurrent()
        repo.access.value = allowed.copy(scope = ScannerScope(UserId("other"), 2)); runCurrent()
        model.manual(code); runCurrent(); assertEquals(2, repo.calls.size)
        repo.calls[0].resume(CheckInOutcome.Admitted(record)); runCurrent()
        assertEquals(ScannerPhase.SUBMITTING, model.state.value.phase)
        model.manual(code); runCurrent(); assertEquals(2, repo.calls.size)
        repo.calls[1].resume(CheckInOutcome.Invalid(InvalidTicketReason.UNKNOWN)); runCurrent()
        assertEquals(CheckInOutcome.Invalid(InvalidTicketReason.UNKNOWN), model.state.value.result)
        model.close()
    }
    @Test fun backgroundOrCloseStopsAnalysisAndDiscardsPendingAndSavedResultFlags() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.requestCamera(); model.permission(ScannerPermission.GRANTED)
        assertTrue(model.state.value.cameraActive)
        model.foreground(false); assertFalse(model.state.value.cameraActive)
        model.foreground(true); assertEquals(ScannerPhase.READY, model.state.value.phase)
        model.manual(code); runCurrent(); model.close()
        repo.calls[0].resume(CheckInOutcome.Admitted(record)); runCurrent()
        assertNull(model.state.value.result); assertNull(model.state.value.access)
        val fresh = ScannerController(repo, backgroundScope); fresh.bind(id); runCurrent(); fresh.foreground(true)
        assertEquals(ScannerPhase.READY, fresh.state.value.phase); assertNull(fresh.state.value.result); assertEquals(1, repo.calls.size)
        fresh.close()
    }
    @Test fun invalidManualFormatNeverCallsBackendOrBecomesInvalidTicket() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.manual("DEMO-123")
        assertEquals(CheckInError.INVALID_INPUT, model.state.value.error); assertNull(model.state.value.result); assertTrue(repo.calls.isEmpty())
        model.close()
    }
    @Test fun forbiddenCommandStopsScannerEvenIfAccessObserverHasNotCaughtUp() = runTest {
        val repo = Repository(); val model = ScannerController(repo, backgroundScope)
        model.bind(id); runCurrent(); model.foreground(true); model.manual(code); runCurrent()
        repo.calls[0].resume(CheckInOutcome.Failed(CheckInError.FORBIDDEN)); runCurrent()
        assertEquals(ScannerPhase.ACCESS_DENIED, model.state.value.phase); assertNull(model.state.value.result); assertNull(model.state.value.access)
        model.manual(code); assertEquals(1, repo.calls.size); model.close()
    }
    @Test fun manualFormatIsStableFromSecretAndRedactedAndNotDisplayNumber() {
        val value = ManualCodePolicy.fromQr("secret")
        assertEquals(value, ManualCodePolicy.fromQr("secret")); assertNotEquals(value, ManualCodePolicy.fromQr("another-secret"))
        assertEquals(value, ManualCodePolicy.parse(value.value.lowercase().chunked(4).joinToString("-")))
        assertNull(ManualCodePolicy.parse("DEMO-123")); assertNull(ManualCodePolicy.parse("O0I1badvalue"))
        assertFalse(QrToken("secret").toString().contains("secret")); assertFalse(value.toString().contains(value.value))
        assertFalse(TicketCredential.Manual(value).toString().contains(value.value))
    }
}
