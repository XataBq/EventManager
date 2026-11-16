package ru.eventpass.app.feature.tickets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import ru.eventpass.core.domain.TicketRepository
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

data class TicketsUiState(val loading: Boolean = true, val data: MyTicketsResult.Ready? = null,
    val refreshing: Boolean = false,
    val error: ProductReadError? = null, val authenticationRequired: Boolean = false,
    val selected: String? = null, val history: Boolean = false, val fullscreen: Boolean = false,
    val confirming: Boolean = false, val cancelling: Boolean = false, val cancelError: TicketError? = null)

@HiltViewModel
class TicketsViewModel @Inject constructor(private val repository: TicketRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(TicketsUiState(selected = saved["ticketSelection"], history = saved["history"] ?: false))
    val state = mutable.asStateFlow()
    private var active = false; private var owner = 0L
    private var ownObserver: Job? = null
    private var observer: Job? = null; private var read: Job? = null; private var command: Job? = null
    fun start() {
        if (active) return
        active = true; val generation = owner
        mutable.value = state.value.copy(loading = state.value.data == null)
        observer = viewModelScope.launch { repository.observeMyTickets(if(state.value.history) TicketScope.PAST else TicketScope.UPCOMING).collect { result -> if (generation == owner) {
            val s = mutable.value
            mutable.value = when (result) {
                is MyTicketsResult.Ready -> {
                    val selected = s.selected?.takeIf { id -> result.tickets.any { it.snapshot.ticketId.value == id } || ownObserver?.isActive == true }
                    val ticket = result.tickets.firstOrNull { it.snapshot.ticketId.value == selected }
                    val qr = ticket?.let { t -> t.canDisplayQr(result.events[t.snapshot.eventId], result.now) } == true
                    if (selected == null) saved.remove<String>("ticketSelection")
                    s.copy(loading = false, data = if(selected != null && result.tickets.none { it.snapshot.ticketId.value == selected }) {
                        val old = s.data?.tickets?.firstOrNull { it.snapshot.ticketId.value == selected }
                        if(old != null) result.copy(tickets = result.tickets + old) else result
                    } else result, error = null, authenticationRequired = false,
                        selected = selected, fullscreen = s.fullscreen && qr)
                }
                is MyTicketsResult.Failed -> if(result.error == ProductReadError.FORBIDDEN) s.copy(loading = false, data = null, selected = null, error = result.error, fullscreen = false) else s.copy(loading = false, error = result.error, fullscreen = false)
                else -> TicketsUiState(loading = false, authenticationRequired = true)
            }
        } } }
        state.value.selected?.let { observeSelected(it) }
    }
    fun stop() {
        active = false; owner++; ownObserver?.cancel(); observer?.cancel(); read?.cancel(); command?.cancel()
        mutable.value = state.value.copy(loading = false, refreshing = false, cancelling = false, confirming = false, fullscreen = false)
    }
    fun select(id: String?) { ownObserver?.cancel(); saved["ticketSelection"] = id; mutable.value = mutable.value.copy(selected = id, fullscreen = false, cancelError = null); if(id != null && active) observeSelected(id) }
    private fun observeSelected(id: String) {
        ownObserver?.cancel(); val generation = owner
        ownObserver = viewModelScope.launch { repository.observeOwnTicket(TicketId(id)).collect { result ->
            if(generation != owner || state.value.selected != id) return@collect
            when(result) {
                is OwnTicketResult.Found -> {
                    val data = state.value.data ?: MyTicketsResult.Ready(emptyList(), now = result.now)
                    mutable.value = state.value.copy(data = data.copy(tickets = if(data.tickets.any { it.snapshot.ticketId.value == id }) data.tickets.map { if(it.snapshot.ticketId.value == id) result.ticket else it } else data.tickets + result.ticket,
                        events = result.event?.let { data.events + (it.id to it) } ?: data.events), loading = false)
                }
                OwnTicketResult.NotFound -> {
                    mutable.value = state.value.copy(data = state.value.data?.let { it.copy(tickets = it.tickets.filterNot { t -> t.snapshot.ticketId.value == id }) })
                    select(null)
                }
                OwnTicketResult.AuthenticationRequired -> mutable.value = TicketsUiState(loading = false, authenticationRequired = true)
                is OwnTicketResult.Failed -> mutable.value = if(result.error == ProductReadError.FORBIDDEN) state.value.copy(
                    data = state.value.data?.let { it.copy(tickets = it.tickets.filterNot { t -> t.snapshot.ticketId.value == id }) },
                    selected = null, error = result.error, fullscreen = false) else state.value.copy(error = result.error, fullscreen = false)
            }
        } }
    }
    fun selectIssued(id: String) { history(false); select(id) }
    fun history(value: Boolean) { val restart = active; if(value != state.value.history && restart) stop(); saved["history"] = value; saved.remove<String>("ticketSelection"); mutable.value = mutable.value.copy(history = value, selected = null, fullscreen = false); if(restart) start() }
    fun fullscreen(value: Boolean) { mutable.value = mutable.value.copy(fullscreen = value) }
    fun confirm(value: Boolean) { if (!mutable.value.cancelling) mutable.value = mutable.value.copy(confirming = value, cancelError = null) }
    fun refresh() {
        if (!active || read?.isActive == true) return
        val generation = owner
        mutable.value = state.value.copy(refreshing = !state.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val selected = state.value.selected
                if(selected != null && state.value.data?.tickets?.any { it.snapshot.ticketId.value == selected && it.summary != null } == true) {
                    val result = repository.observeOwnTicket(TicketId(selected)).first()
                    if(generation == owner) when(result) {
                        is OwnTicketResult.Found -> {
                            val data = state.value.data
                            if(data != null) mutable.value = state.value.copy(data = data.copy(tickets = data.tickets.map { if(it.snapshot.ticketId.value == selected) result.ticket else it }), error = null,
                                fullscreen = state.value.fullscreen && result.ticket.canDisplayQr(result.event, result.now))
                        }
                        OwnTicketResult.NotFound -> select(null)
                        OwnTicketResult.AuthenticationRequired -> mutable.value = TicketsUiState(loading = false, authenticationRequired = true)
                        is OwnTicketResult.Failed -> mutable.value = if(result.error == ProductReadError.FORBIDDEN) state.value.copy(
                            data = state.value.data?.let { it.copy(tickets = it.tickets.filterNot { t -> t.snapshot.ticketId.value == selected }) },
                            selected = null, error = result.error, fullscreen = false) else state.value.copy(error = result.error, fullscreen = false)
                    }
                } else repository.refreshMyTickets(if(state.value.history) TicketScope.PAST else TicketScope.UPCOMING)
            }
            finally { if (generation == owner) mutable.value = state.value.copy(loading = false, refreshing = false) }
        }
        read = job; job.start()
    }
    fun cancel() {
        val s = mutable.value; val id = s.selected ?: return
        if (!active || s.cancelling) return
        val generation = owner
        mutable.value = s.copy(cancelling = true, cancelError = null)
        command = viewModelScope.launch {
            try { val result = repository.cancelOwnTicket(TicketId(id)); currentCoroutineContext().ensureActive()
                if (generation != owner) return@launch
                when (result) {
                is CancelTicketOutcome.Cancelled -> {
                    mutable.value = mutable.value.copy(confirming = false, fullscreen = false)
                    history(true)
                }
                is CancelTicketOutcome.Failed -> mutable.value = mutable.value.copy(cancelError = result.error, confirming = false)
            } } finally { if (generation == owner) mutable.value = mutable.value.copy(cancelling = false) }
        }
    }
}
