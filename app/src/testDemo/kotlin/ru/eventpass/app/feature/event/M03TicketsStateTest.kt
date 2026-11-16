package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.app.feature.tickets.TicketsViewModel
import ru.eventpass.core.domain.TicketRepository
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

@OptIn(ExperimentalCoroutinesApi::class)
class M03TicketsStateTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val snapshot = GuestTicketSnapshot(TicketId("t1"),EventId("e1"),"Event",now,"Europe/Moscow","Hall","Alex","EVT-123456","qr")
    private val ticket = Ticket(snapshot,"",TicketStatus.ACTIVE,TicketEventSummary(EventId("e1"),EventSlug("event"),EventLifecycle.LIVE,now.plusSeconds(3600),null))
    private class ScopedRepo : TicketRepository {
        val lists = MutableSharedFlow<MyTicketsResult>(replay = 1)
        val own = MutableSharedFlow<OwnTicketResult>(replay = 1)
        override fun observeMyTickets() = lists
        override fun observeOwnTicket(ticketId: TicketId) = own
        override suspend fun refreshMyTickets() = error("Unexpected list refresh")
        override suspend fun cancelOwnTicket(ticketId: TicketId) = CancelTicketOutcome.Failed(TicketError.FEATURE_UNAVAILABLE)
    }
    @Test fun outOfScopeCardArrivingAfterListDoesNotChangeServerItemsOrCounters() = outOfScopeCard(listFirst = true)
    @Test fun outOfScopeCardArrivingBeforeListDoesNotChangeServerItemsOrCounters() = outOfScopeCard(listFirst = false)
    private fun outOfScopeCard(listFirst: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = ScopedRepo()
        val list = MyTicketsResult.Ready(emptyList(), now = now, scope = TicketScope.UPCOMING,
            counters = mapOf(TicketScope.UPCOMING to 0, TicketScope.PAST to 1))
        val past = ticket.copy(summary = ticket.summary!!.copy(status = EventLifecycle.FINISHED, finishedAt = now))
        val vm = TicketsViewModel(repo, SavedStateHandle())
        try {
            vm.start(); runCurrent()
            if (listFirst) { repo.lists.emit(list); runCurrent() }
            vm.select(past.snapshot.ticketId.value); runCurrent()
            repo.own.emit(OwnTicketResult.Found(past, null, now)); runCurrent()
            if (!listFirst) { repo.lists.emit(list); runCurrent() }
            assertEquals(past.snapshot.ticketId.value, vm.state.value.selected)
            assertEquals(past, vm.state.value.selectedTicket)
            assertEquals(now, vm.state.value.selectedNow)
            assertEquals(list, vm.state.value.data)
            vm.select(null); runCurrent()
            assertNull(vm.state.value.selected)
            assertNull(vm.state.value.selectedTicket)
            assertNull(vm.state.value.selectedCard)
            assertEquals(list, vm.state.value.data)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun restoredOutOfScopeSelectionSurvivesImmediateListBeforeOwnCard() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = ScopedRepo()
        val list = MyTicketsResult.Ready(emptyList(), now = now, scope = TicketScope.UPCOMING)
        repo.lists.emit(list)
        val saved = SavedStateHandle(mapOf("ticketSelection" to "t1"))
        val vm = TicketsViewModel(repo, saved)
        try {
            vm.start()
            assertEquals("t1", vm.state.value.selected)
            assertEquals("t1", saved.get<String>("ticketSelection"))
            repo.own.emit(OwnTicketResult.Found(ticket, null, now)); runCurrent()
            assertEquals("t1", vm.state.value.selected)
            assertEquals(ticket, vm.state.value.selectedTicket)
            assertEquals(list, vm.state.value.data)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    private class Repo(val ticket: Ticket, val now: Instant) : TicketRepository {
        val own = MutableStateFlow<OwnTicketResult>(OwnTicketResult.Found(ticket,null,now))
        var refreshed: OwnTicketResult? = null
        var cardReads=0; var listRefreshes=0
        override fun observeMyTickets() = flowOf(MyTicketsResult.Ready(listOf(ticket),now=now,scope=TicketScope.UPCOMING))
        override fun observeOwnTicket(ticketId: TicketId): Flow<OwnTicketResult> = flow {
            cardReads++
            val result = refreshed
            if (result != null) emit(result) else emitAll(own)
        }
        override suspend fun refreshMyTickets() { listRefreshes++ }
        override suspend fun cancelOwnTicket(ticketId: TicketId) = CancelTicketOutcome.Failed(TicketError.FEATURE_UNAVAILABLE)
    }
    @Test fun selectedServerCardRefreshDoesNotRefreshNeighboringList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(ticket,now); val vm=TicketsViewModel(repo,SavedStateHandle())
        try {
            vm.start(); runCurrent(); vm.select("t1"); runCurrent(); val before=repo.cardReads
            val list = vm.state.value.data
            val updated = ticket.copy(status = TicketStatus.USED, checkedInAt = now.plusSeconds(60))
            repo.refreshed = OwnTicketResult.Found(updated, null, now.plusSeconds(60))
            vm.fullscreen(true)
            vm.refresh(); runCurrent()
            assertEquals(before+1,repo.cardReads); assertEquals(0,repo.listRefreshes)
            assertEquals("t1",vm.state.value.selected); assertFalse(vm.state.value.refreshing)
            assertEquals(updated, vm.state.value.selectedTicket)
            assertEquals(now.plusSeconds(60), vm.state.value.selectedNow)
            assertEquals(list, vm.state.value.data); assertFalse(vm.state.value.fullscreen)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun forbiddenCardDropsPrivateTicketAndQrInsteadOfKeepingStaleData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(ticket,now); val vm=TicketsViewModel(repo,SavedStateHandle())
        try {
            vm.start(); runCurrent(); vm.select("t1"); runCurrent(); vm.fullscreen(true)
            repo.own.value=OwnTicketResult.Failed(ProductReadError.FORBIDDEN); runCurrent()
            assertNull(vm.state.value.selected); assertFalse(vm.state.value.fullscreen)
            assertNull(vm.state.value.selectedCard); assertNull(vm.state.value.selectedTicket)
            assertTrue(vm.state.value.data!!.tickets.isEmpty()); assertEquals(ProductReadError.FORBIDDEN,vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
