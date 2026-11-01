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
    val input: String = "", val revoke: Volunteer? = null, val busy: Boolean = false,
    val error: VolunteerError? = null, val denied: Boolean = false)

@HiltViewModel
class VolunteersViewModel @Inject constructor(private val repository: VolunteerRepository) : ViewModel() {
    private val mutable = MutableStateFlow(VolunteersUiState()); val state = mutable.asStateFlow()
    private var eventId: EventId? = null
    private var active = false
    private var observer: Job? = null; private var command: Job? = null; private var generation = 0L
    fun start(id: String) {
        stop(); active = true; eventId = EventId(id)
        observer = viewModelScope.launch { repository.observeVolunteers(EventId(id)).collect { result ->
            when (result) {
                is VolunteersResult.Ready -> mutable.value = mutable.value.copy(loading = false, volunteers = result.volunteers, error = null)
                is VolunteersResult.Failed -> fail(result.error)
            }
        } }
    }
    fun stop() { active = false; generation++; observer?.cancel(); command?.cancel(); mutable.value = VolunteersUiState() }
    fun retry() { eventId?.let { start(it.value) } }
    fun edit(value: String) { if (!state.value.busy) mutable.value = state.value.copy(input = value.take(254), error = null) }
    fun confirm(value: Volunteer?) { if (!state.value.busy) mutable.value = state.value.copy(revoke = value) }
    fun grant() { val value = state.value.input.trim(); if (value.isNotBlank()) send { repository.grant(it, value) } }
    fun revoke() { val user = state.value.revoke?.grant?.userId ?: return; send { repository.revoke(it, user) } }
    private fun send(action: suspend (EventId) -> VolunteerOutcome) {
        val id = eventId ?: return; val token = generation
        if (state.value.loading || state.value.busy || state.value.denied || !active) return
        mutable.value = state.value.copy(busy = true, error = null)
        command = viewModelScope.launch {
            try {
                val result = action(id); currentCoroutineContext().ensureActive()
                if (generation != token) return@launch
                when (result) {
                    is VolunteerOutcome.Updated -> mutable.value = state.value.copy(input = "", revoke = null)
                    is VolunteerOutcome.Failed -> fail(result.error)
                }
            } finally { if (generation == token) mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun fail(error: VolunteerError) {
        val denied = error in setOf(VolunteerError.AUTHENTICATION_REQUIRED, VolunteerError.FORBIDDEN,
            VolunteerError.SESSION_CHANGED, VolunteerError.NOT_FOUND)
        mutable.value = if (denied) VolunteersUiState(loading = false, error = error, denied = true)
        else state.value.copy(loading = false, error = error)
    }
}
