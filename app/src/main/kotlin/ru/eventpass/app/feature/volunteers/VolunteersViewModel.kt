package ru.eventpass.app.feature.volunteers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*

data class VolunteersUiState(val loading: Boolean = true, val volunteers: List<Volunteer> = emptyList(),
    val refreshing: Boolean = false, val readError: VolunteerError? = null,
    val input: String = "", val revoke: Volunteer? = null, val busy: Boolean = false,
    val error: VolunteerError? = null, val denied: Boolean = false, val revokeError: VolunteerError? = null)

@HiltViewModel
class VolunteersViewModel @Inject constructor(private val repository: VolunteerRepository) : ViewModel() {
    private val mutable = MutableStateFlow(VolunteersUiState()); val state = mutable.asStateFlow()
    private var eventId: EventId? = null
    private var active = false
    private var observer: Job? = null; private var command: Job? = null; private var generation = 0L
    private var read: Job? = null; private var observation = 0L
    fun start(id: String) {
        stop(); active = true; eventId = EventId(id)
        val token = generation
        observer = viewModelScope.launch { repository.observeVolunteers(EventId(id)).collect { result -> if (token == generation) {
            observation++; accept(result)
        } } }
    }
    fun stop() { active = false; generation++; observer?.cancel(); command?.cancel(); read?.cancel(); observation = 0; mutable.value = VolunteersUiState() }
    fun retry() = refresh()
    private fun accept(result: VolunteersResult) {
        when (result) {
            is VolunteersResult.Ready -> mutable.value = state.value.copy(loading = false, volunteers = result.volunteers, readError = null)
            is VolunteersResult.Failed -> fail(result.error, reading = true)
        }
    }
    fun refresh() {
        val id = eventId ?: return
        if (!active || read?.isActive == true || state.value.busy || state.value.revoke != null) return
        val token = generation; val started = observation
        mutable.value = state.value.copy(refreshing = !state.value.loading, readError = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.refreshVolunteers(id); currentCoroutineContext().ensureActive()
                if (token == generation && observation == started) accept(result)
            } finally { if (token == generation) mutable.value = state.value.copy(loading = false, refreshing = false) }
        }
        read = job; job.start()
    }
    fun edit(value: String) { if (!state.value.busy) mutable.value = state.value.copy(input = value.take(254), error = null) }
    fun confirm(value: Volunteer?) { if (!state.value.busy) mutable.value = state.value.copy(revoke = value, revokeError = null, error = null) }
    fun grant() { val value = state.value.input.trim(); if (value.isNotBlank()) send { repository.grant(it, value) } }
    fun revoke() { val user = state.value.revoke?.grant?.userId ?: return; send(revoking = true) { repository.revoke(it, user) } }
    private fun send(revoking: Boolean = false, action: suspend (EventId) -> VolunteerOutcome) {
        val id = eventId ?: return; val token = generation
        if (state.value.loading || state.value.busy || state.value.denied || !active) return
        observation++
        mutable.value = state.value.copy(busy = true, error = null, revokeError = null)
        command = viewModelScope.launch {
            try {
                val result = action(id); currentCoroutineContext().ensureActive()
                if (generation != token) return@launch
                when (result) {
                    is VolunteerOutcome.Updated, is VolunteerOutcome.Granted, is VolunteerOutcome.Revoked -> mutable.value = state.value.copy(input = "", revoke = null, revokeError = null)
                    is VolunteerOutcome.Failed -> if (revoking && result.error in setOf(VolunteerError.NETWORK,
                        VolunteerError.TIMEOUT, VolunteerError.STORAGE_UNAVAILABLE))
                        mutable.value = state.value.copy(revokeError = result.error)
                    else fail(result.error)
                }
            } finally { if (generation == token) mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun fail(error: VolunteerError, reading: Boolean = false) {
        val denied = error in setOf(VolunteerError.AUTHENTICATION_REQUIRED, VolunteerError.FORBIDDEN,
            VolunteerError.SESSION_CHANGED, VolunteerError.NOT_FOUND,VolunteerError.EVENT_FINISHED)
        if (denied) { stop(); mutable.value = VolunteersUiState(loading = false, error = error, denied = true) }
        else mutable.value = if (reading) state.value.copy(loading = false, readError = error) else state.value.copy(loading = false, error = error)
    }
}
