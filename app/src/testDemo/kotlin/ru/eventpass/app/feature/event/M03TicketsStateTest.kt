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
    private class Repo(val ticket: Ticket, val now: Instant) : TicketRepository {
        val own = MutableStateFlow<OwnTicketResult>(OwnTicketResult.Found(ticket,null,now))
        var cardReads=0; var listRefreshes=0
        override fun observeMyTickets() = flowOf(MyTicketsResult.Ready(listOf(ticket),now=now,scope=TicketScope.UPCOMING))
        override fun observeOwnTicket(ticketId: TicketId): Flow<OwnTicketResult> = own.onStart { cardReads++ }
        override suspend fun refreshMyTickets() { listRefreshes++ }
        override suspend fun cancelOwnTicket(ticketId: TicketId) = CancelTicketOutcome.Failed(TicketError.FEATURE_UNAVAILABLE)
    }
    @Test fun selectedServerCardRefreshDoesNotRefreshNeighboringList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(ticket,now); val vm=TicketsViewModel(repo,SavedStateHandle())
        try {
            vm.start(); runCurrent(); vm.select("t1"); runCurrent(); val before=repo.cardReads
            vm.refresh(); runCurrent()
            assertEquals(before+1,repo.cardReads); assertEquals(0,repo.listRefreshes)
            assertEquals("t1",vm.state.value.selected); assertFalse(vm.state.value.refreshing)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun forbiddenCardDropsPrivateTicketAndQrInsteadOfKeepingStaleData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val repo=Repo(ticket,now); val vm=TicketsViewModel(repo,SavedStateHandle())
        try {
            vm.start(); runCurrent(); vm.select("t1"); runCurrent(); vm.fullscreen(true)
            repo.own.value=OwnTicketResult.Failed(ProductReadError.FORBIDDEN); runCurrent()
            assertNull(vm.state.value.selected); assertFalse(vm.state.value.fullscreen)
            assertTrue(vm.state.value.data!!.tickets.isEmpty()); assertEquals(ProductReadError.FORBIDDEN,vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
