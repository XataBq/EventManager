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
        var refreshAction: (suspend () -> DashboardResult)? = null
        override fun observeDashboard(eventId: EventId) = stream
        override suspend fun refreshDashboard(eventId: EventId) = refreshAction?.invoke() ?: refresh ?: stream.value
        override suspend fun attendees(eventId: EventId, query: String, cursor: AttendanceCursor?) = load(query)
        override suspend fun cancelRegistration(eventId: EventId, registrationId: String) = cancel(registrationId)
    }
    @Test fun lateRefreshCannotRollBackObserverTotalsOrEmptySearchPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo()
        val pending=mutableListOf<Continuation<DashboardResult>>()
        repo.refreshAction={ suspendCoroutine { pending+=it } }
        repo.load={ AttendeesResult.Page((repo.stream.value as DashboardResult.Ready).dashboard.revision,emptyList(),0,null) }
        val vm=DashboardViewModel(repo,SavedStateHandle(mapOf("query" to "no-match")))
        try {
            vm.start("event"); runCurrent(); vm.refresh(); runCurrent()
            val latest=dashboard("after-checkin").copy(summary=AttendanceSummary(1,1,0,19))
            repo.stream.value=DashboardResult.Ready(latest); runCurrent()
            assertEquals(1,vm.state.value.dashboard!!.summary.checkedIn)
            pending.single().resume(DashboardResult.Ready(dashboard())); runCurrent()
            assertEquals(latest,vm.state.value.dashboard); assertEquals(0,vm.state.value.matches)
            assertTrue(vm.state.value.rows.isEmpty()); assertFalse(vm.state.value.refreshing); assertFalse(vm.state.value.paging)
            assertNull(vm.state.value.readError); assertNull(vm.state.value.pageError)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun lateRefreshFailureCannotReplaceNewerObserverError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo()
        val pending=mutableListOf<Continuation<DashboardResult>>(); repo.refreshAction={ suspendCoroutine { pending+=it } }
        val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("event"); runCurrent(); vm.refresh(); runCurrent()
            repo.stream.value=DashboardResult.Failed(AttendanceError.TIMEOUT); runCurrent()
            pending.single().resume(DashboardResult.Failed(AttendanceError.NETWORK)); runCurrent()
            assertEquals(AttendanceError.TIMEOUT,vm.state.value.readError)
            assertFalse(vm.state.value.refreshing); assertEquals(listOf(row),vm.state.value.rows)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun pageRefreshRequestedWhileBusyRunsOnceAfterOldRefreshFinishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo()
        val pending=mutableListOf<Continuation<DashboardResult>>(); var calls=0
        val latest=dashboard("page-C").copy(summary=AttendanceSummary(2,1,1,18))
        repo.refreshAction={ calls++; if (calls==1) suspendCoroutine { pending+=it } else DashboardResult.Ready(latest) }
        val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("event"); runCurrent(); vm.refresh(); runCurrent()
            repo.load={ AttendeesResult.Page(latest.revision,listOf(row.copy(status=TicketStatus.USED)),1,null) }
            repo.stream.value=DashboardResult.Ready(dashboard("observer-B")); runCurrent()
            assertTrue(vm.state.value.refreshing); assertEquals(1,calls)
            // The mismatched page requested a read while refresh A was still pending.
            pending.single().resume(DashboardResult.Ready(dashboard())); runCurrent()
            assertEquals(2,calls); assertEquals(latest,vm.state.value.dashboard)
            assertEquals(TicketStatus.USED,vm.state.value.rows.single().status)
            assertFalse(vm.state.value.paging); assertFalse(vm.state.value.refreshing); assertNull(vm.state.value.pageError)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun oldRefreshFinallyCannotUnlockNewOwnerOrReplayItsQueuedRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo()
        val pending=mutableListOf<Continuation<DashboardResult>>(); repo.refreshAction={ suspendCoroutine { pending+=it } }
        val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("one"); runCurrent(); vm.refresh(); runCurrent(); vm.refresh()
            vm.stop(); vm.start("two"); runCurrent(); vm.refresh(); runCurrent()
            assertEquals(2,pending.size)
            pending[0].resume(DashboardResult.Ready(dashboard("old"))); runCurrent()
            assertTrue(vm.state.value.refreshing); assertEquals(2,pending.size)
            pending[1].resume(DashboardResult.Ready(dashboard())); runCurrent()
            assertFalse(vm.state.value.refreshing); assertEquals(2,pending.size)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun stopCancelsQueuedFollowUpAfterImmediateRefreshReply() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler)); val repo=Repo()
        var calls=0; var cancelled=false
        val vm=DashboardViewModel(repo,SavedStateHandle())
        try {
            vm.start("event")
            repo.load={ AttendeesResult.Page("page-C",emptyList(),0,null) }
            repo.refreshAction={
                calls++
                if (calls==1) DashboardResult.Ready(dashboard("refresh-B"))
                else try { awaitCancellation() } finally { cancelled=true }
            }
            vm.refresh()
            assertEquals(2,calls); assertTrue(vm.state.value.refreshing)
            vm.stop(); runCurrent()
            assertTrue(cancelled); assertNull(vm.state.value.dashboard); assertFalse(vm.state.value.refreshing)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
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
