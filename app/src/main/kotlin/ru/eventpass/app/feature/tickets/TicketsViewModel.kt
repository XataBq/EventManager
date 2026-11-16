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
    val confirming: Boolean = false, val cancelling: Boolean = false, val cancelError: TicketError? = null,
    val selectedCard: OwnTicketResult.Found? = null) {
    // A separately fetched card never changes the server's scoped list or counters.
    val selectedTicket: Ticket? get() = selectedCard?.ticket
        ?: data?.tickets?.firstOrNull { it.snapshot.ticketId.value == selected }
    val selectedEvent: Event? get() = if (selectedCard != null) selectedCard.event
        else data?.events?.get(selectedTicket?.snapshot?.eventId)
    val selectedNow: java.time.Instant get() = selectedCard?.now ?: data?.now ?: java.time.Instant.EPOCH
}

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
        // Register the independent card owner before a list can emit synchronously on Main.immediate.
        state.value.selected?.let { observeSelected(it) }
        observer = viewModelScope.launch { repository.observeMyTickets(if(state.value.history) TicketScope.PAST else TicketScope.UPCOMING).collect { result -> if (generation == owner) {
            val s = mutable.value
            mutable.value = when (result) {
                is MyTicketsResult.Ready -> {
                    val selected = s.selected?.takeIf { id -> result.tickets.any { it.snapshot.ticketId.value == id } || s.selectedCard != null || ownObserver?.isActive == true }
                    if (selected == null) saved.remove<String>("ticketSelection")
                    val next = s.copy(loading = false, data = result, error = null, authenticationRequired = false,
                        selected = selected, selectedCard = s.selectedCard.takeIf { selected != null })
                    next.copy(fullscreen = s.fullscreen && next.selectedTicket?.canDisplayQr(next.selectedEvent, next.selectedNow) == true)
                }
                is MyTicketsResult.Failed -> if(result.error == ProductReadError.FORBIDDEN) {
                    saved.remove<String>("ticketSelection"); ownObserver?.cancel()
                    s.copy(loading = false, data = null, selected = null, selectedCard = null, error = result.error, fullscreen = false)
                } else s.copy(loading = false, error = result.error, fullscreen = false)
                else -> { saved.remove<String>("ticketSelection"); ownObserver?.cancel(); TicketsUiState(loading = false, authenticationRequired = true) }
            }
        } } }
    }
    fun stop() {
        active = false; owner++; ownObserver?.cancel(); observer?.cancel(); read?.cancel(); command?.cancel()
        mutable.value = state.value.copy(loading = false, refreshing = false, cancelling = false, confirming = false, fullscreen = false)
    }
    fun select(id: String?) { ownObserver?.cancel(); saved["ticketSelection"] = id; mutable.value = mutable.value.copy(selected = id, selectedCard = null, fullscreen = false, cancelError = null); if(id != null && active) observeSelected(id) }
    private fun observeSelected(id: String) {
        ownObserver?.cancel(); val generation = owner
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { repository.observeOwnTicket(TicketId(id)).collect { result ->
            if(generation != owner || state.value.selected != id) return@collect
            applyOwnTicket(id, result)
        } }
        ownObserver = job; job.start()
    }
    private fun applyOwnTicket(id: String, result: OwnTicketResult) {
        val s = state.value
        when (result) {
            is OwnTicketResult.Found -> mutable.value = s.copy(selectedCard = result, loading = false, error = null,
                fullscreen = s.fullscreen && result.ticket.canDisplayQr(result.event, result.now))
            OwnTicketResult.NotFound -> dropSelected(id)
            OwnTicketResult.AuthenticationRequired -> {
                saved.remove<String>("ticketSelection"); ownObserver?.cancel()
                mutable.value = TicketsUiState(loading = false, authenticationRequired = true)
            }
            is OwnTicketResult.Failed -> if (result.error == ProductReadError.FORBIDDEN) dropSelected(id, result.error)
                else mutable.value = s.copy(error = result.error, fullscreen = false)
        }
    }
    private fun dropSelected(id: String, error: ProductReadError? = null) {
        select(null)
        mutable.value = state.value.copy(data = state.value.data?.let {
            it.copy(tickets = it.tickets.filterNot { t -> t.snapshot.ticketId.value == id })
        }, loading = false, error = error)
    }
    fun selectIssued(id: String) { history(false); select(id) }
    fun history(value: Boolean) { val restart = active; if(value != state.value.history && restart) stop(); ownObserver?.cancel(); saved["history"] = value; saved.remove<String>("ticketSelection"); mutable.value = mutable.value.copy(history = value, selected = null, selectedCard = null, fullscreen = false); if(restart) start() }
    fun fullscreen(value: Boolean) { mutable.value = mutable.value.copy(fullscreen = value) }
    fun confirm(value: Boolean) { if (!mutable.value.cancelling) mutable.value = mutable.value.copy(confirming = value, cancelError = null) }
    fun refresh() {
        if (!active || read?.isActive == true) return
        val generation = owner
        mutable.value = state.value.copy(refreshing = !state.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val selected = state.value.selected
                if(selected != null && state.value.selectedTicket?.summary != null) {
                    val result = repository.observeOwnTicket(TicketId(selected)).first()
                    currentCoroutineContext().ensureActive()
                    if(generation == owner && state.value.selected == selected) applyOwnTicket(selected, result)
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
