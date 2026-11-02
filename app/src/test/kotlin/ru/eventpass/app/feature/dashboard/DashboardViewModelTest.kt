package ru.eventpass.app.feature.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.TicketStatus
import java.time.Instant
import kotlin.coroutines.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val row = Attendee("registration",TicketId("ticket"),"Alex","alex@example.com","DEMO-1",TicketStatus.ACTIVE,Instant.EPOCH,null,null)
    private fun dashboard(revision: String = "revision") = AttendanceDashboard(AttendanceHeader(EventId("event"),"Title",Instant.EPOCH,"UTC","Hall",EventLifecycle.PUBLISHED,true,false),AttendanceSummary(1,0,1,19),revision,Instant.EPOCH)
    private inner class Repo : AttendanceRepository {
        val stream = MutableStateFlow<DashboardResult>(DashboardResult.Ready(dashboard()))
        var load: suspend (String) -> AttendeesResult = { AttendeesResult.Page("revision",listOf(row),1,null) }
        var cancel: suspend (String) -> OwnerCancelResult = { OwnerCancelResult.Cancelled }
        var refresh: DashboardResult? = null
        override fun observeDashboard(eventId: EventId) = stream
        override suspend fun refreshDashboard(eventId: EventId) = refresh ?: stream.value
        override suspend fun attendees(eventId: EventId, query: String, cursor: AttendanceCursor?) = load(query)
        override suspend fun cancelRegistration(eventId: EventId, registrationId: String) = cancel(registrationId)
    }
    @Test fun oldQueryAndFinallyCannotReplaceOrUnlockNewPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo()
        val pending=mutableListOf<Pair<String,Continuation<AttendeesResult>>>()
        repo.load={ query -> suspendCoroutine { pending += query to it } }
        val saved=SavedStateHandle(); val vm=DashboardViewModel(repo,saved)
        try {
            vm.start("event"); runCurrent(); vm.query("old"); runCurrent(); vm.query("new"); runCurrent()
            pending[0].second.resume(AttendeesResult.Page("revision",listOf(row),1,null))
            pending[1].second.resume(AttendeesResult.Failed(AttendanceError.NETWORK)); runCurrent()
            assertTrue(vm.state.value.paging); assertTrue(vm.state.value.rows.isEmpty()); assertNull(vm.state.value.pageError)
            pending[2].second.resume(AttendeesResult.Page("revision",listOf(row.copy(name="New")),1,null)); runCurrent()
            assertEquals("New",vm.state.value.rows.single().name); assertFalse(vm.state.value.paging)
            assertEquals(setOf("query"),saved.keys()); assertEquals("new",saved.get<String>("query"))
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun hiddenOwnerAndLateCancelFinallyCannotChangeNewConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo()
        val pending=mutableListOf<Continuation<OwnerCancelResult>>(); repo.cancel={ suspendCoroutine { pending+=it } }
        val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("one"); runCurrent(); vm.confirm(row); vm.cancel(); runCurrent()
            vm.stop(); assertNull(vm.state.value.dashboard); assertTrue(vm.state.value.rows.isEmpty()); assertNull(vm.state.value.selected)
            vm.start("two"); runCurrent(); val next=row.copy(registrationId="another",name="Another")
            vm.confirm(next); vm.cancel(); runCurrent()
            pending[0].resume(OwnerCancelResult.Failed(AttendanceError.NETWORK)); runCurrent()
            assertTrue(vm.state.value.cancelling); assertNull(vm.state.value.cancelError); assertEquals(next,vm.state.value.selected)
            pending[1].resume(OwnerCancelResult.Failed(AttendanceError.TIMEOUT)); runCurrent()
            assertFalse(vm.state.value.cancelling); assertEquals(AttendanceError.TIMEOUT,vm.state.value.cancelError)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun deniedClearsPiiAndConfirmationWhileRefreshErrorKeepsAccessibleSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(); val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("event"); runCurrent(); vm.confirm(row)
            repo.refresh=DashboardResult.Failed(AttendanceError.NETWORK); vm.refresh(); runCurrent()
            assertEquals(listOf(row),vm.state.value.rows); assertNotNull(vm.state.value.dashboard)
            assertEquals(AttendanceError.NETWORK,vm.state.value.readError)
            repo.refresh=DashboardResult.Ready(dashboard().copy(updatedAt=Instant.ofEpochSecond(10))); vm.refresh(); runCurrent()
            assertNull(vm.state.value.readError); assertEquals(Instant.ofEpochSecond(10),vm.state.value.dashboard!!.updatedAt)
            repo.stream.value=DashboardResult.Failed(AttendanceError.FORBIDDEN); runCurrent()
            assertTrue(vm.state.value.denied); assertNull(vm.state.value.dashboard); assertTrue(vm.state.value.rows.isEmpty()); assertNull(vm.state.value.selected)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun newRevisionDropsOldPagesButSearchDoesNotChangeTotals() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(); val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("event"); runCurrent(); vm.query("no match")
            repo.load={ AttendeesResult.Page("revision",emptyList(),0,null) }; runCurrent()
            assertEquals(0,vm.state.value.matches); assertEquals(1,vm.state.value.dashboard!!.summary.registered)
            repo.load={ AttendeesResult.Page("next",listOf(row.copy(status=TicketStatus.USED)),1,null) }
            repo.stream.value=DashboardResult.Ready(dashboard("next").copy(summary=AttendanceSummary(1,1,0,19))); runCurrent()
            assertEquals(listOf(TicketStatus.USED),vm.state.value.rows.map { it.status }); assertEquals(1,vm.state.value.dashboard!!.summary.checkedIn)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
