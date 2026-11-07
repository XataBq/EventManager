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
    private var observer: Job? = null; private var read: Job? = null; private var command: Job? = null
    fun start() {
        if (active) return
        active = true; val generation = owner
        mutable.value = state.value.copy(loading = state.value.data == null)
        observer = viewModelScope.launch { repository.observeMyTickets().collect { result -> if (generation == owner) {
            val s = mutable.value
            mutable.value = when (result) {
                is MyTicketsResult.Ready -> {
                    val selected = s.selected?.takeIf { id -> result.tickets.any { it.snapshot.ticketId.value == id } }
                    val ticket = result.tickets.firstOrNull { it.snapshot.ticketId.value == selected }
                    val qr = ticket?.let { t -> result.events[t.snapshot.eventId]?.let { t.canDisplayQr(it, result.now) } } == true
                    if (selected == null) saved.remove<String>("ticketSelection")
                    s.copy(loading = false, data = result, error = null, authenticationRequired = false,
                        selected = selected, fullscreen = s.fullscreen && qr)
                }
                is MyTicketsResult.Failed -> s.copy(loading = false, error = result.error, fullscreen = false)
                else -> TicketsUiState(loading = false, authenticationRequired = true)
            }
        } } }
    }
    fun stop() {
        active = false; owner++; observer?.cancel(); read?.cancel(); command?.cancel()
        mutable.value = state.value.copy(loading = false, refreshing = false, cancelling = false, confirming = false, fullscreen = false)
    }
    fun select(id: String?) { saved["ticketSelection"] = id; mutable.value = mutable.value.copy(selected = id, fullscreen = false, cancelError = null) }
    fun selectIssued(id: String) { history(false); select(id) }
    fun history(value: Boolean) { saved["history"] = value; saved.remove<String>("ticketSelection"); mutable.value = mutable.value.copy(history = value, selected = null, fullscreen = false) }
    fun fullscreen(value: Boolean) { mutable.value = mutable.value.copy(fullscreen = value) }
    fun confirm(value: Boolean) { if (!mutable.value.cancelling) mutable.value = mutable.value.copy(confirming = value, cancelError = null) }
    fun refresh() {
        if (!active || read?.isActive == true) return
        val generation = owner
        mutable.value = state.value.copy(refreshing = !state.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { repository.refreshMyTickets() }
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
                    saved["history"] = true
                    mutable.value = mutable.value.copy(confirming = false, history = true, fullscreen = false)
                }
                is CancelTicketOutcome.Failed -> mutable.value = mutable.value.copy(cancelError = result.error, confirming = false)
            } } finally { if (generation == owner) mutable.value = mutable.value.copy(cancelling = false) }
        }
    }
}
