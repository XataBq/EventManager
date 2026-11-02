package ru.eventpass.app.feature.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.event.EventId

data class DashboardUiState(val dashboard: AttendanceDashboard? = null, val rows: List<Attendee> = emptyList(),
    val query: String = "", val matches: Int = 0, val next: AttendanceCursor? = null, val loading: Boolean = true,
    val paging: Boolean = false, val refreshing: Boolean = false, val readError: AttendanceError? = null,
    val pageError: AttendanceError? = null, val denied: Boolean = false, val selected: Attendee? = null,
    val cancelling: Boolean = false, val cancelError: AttendanceError? = null)

@HiltViewModel
class DashboardViewModel @Inject constructor(private val repository: AttendanceRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(DashboardUiState(query = saved["query"] ?: "")); val state = mutable.asStateFlow()
    private var id: EventId? = null; private var owner = 0L; private var request = 0L
    private var observer: Job? = null; private var page: Job? = null; private var command: Job? = null; private var refresh: Job? = null
    private var loadedRevision: String? = null; private var active = false
    private var observation = 0L; private var refreshPending = false
    fun start(eventId: String) {
        stop(); active = true; id = EventId(eventId); val generation = owner
        observer = viewModelScope.launch { repository.observeDashboard(EventId(eventId)).collect { if (generation == owner) {
            observation++; accept(it)
        } } }
    }
    fun stop() {
        active = false; owner++; request++; observer?.cancel(); page?.cancel(); command?.cancel(); refresh?.cancel()
        loadedRevision = null; observation = 0; refreshPending = false
        mutable.value = DashboardUiState(query = saved["query"] ?: "")
    }
    private fun denied(error: AttendanceError): Boolean {
        if (error !in setOf(AttendanceError.FORBIDDEN, AttendanceError.AUTHENTICATION_REQUIRED, AttendanceError.SESSION_CHANGED, AttendanceError.NOT_FOUND)) return false
        stop(); mutable.value = state.value.copy(loading = false, denied = true, readError = error); return true
    }
    private fun accept(result: DashboardResult) {
        when (result) {
            is DashboardResult.Failed -> if (!denied(result.error)) mutable.value = state.value.copy(loading = false, readError = result.error)
            is DashboardResult.Ready -> {
                val changed = state.value.dashboard?.revision != result.dashboard.revision
                mutable.value = state.value.copy(dashboard = result.dashboard, loading = false, readError = null)
                if (changed || (loadedRevision == null && !state.value.paging)) load(false)
            }
        }
    }
    fun query(value: String) {
        val text = value.take(254); if (text == state.value.query) return
        saved["query"] = text; mutable.value = state.value.copy(query = text); load(false)
    }
    fun more() { if (!state.value.paging && state.value.next != null) load(true) }
    fun retryPage() = load(state.value.rows.isNotEmpty() && state.value.next != null)
    private fun load(append: Boolean) {
        val eventId = id ?: return; val dashboard = state.value.dashboard ?: return; if (!active) return
        page?.cancel(); val serial = ++request; val generation = owner; val query = state.value.query
        val cursor = if (append) state.value.next else null
        if (!append) { loadedRevision = null; mutable.value = state.value.copy(rows = emptyList(), next = null, matches = 0) }
        mutable.value = state.value.copy(paging = true, pageError = null)
        page = viewModelScope.launch {
            try {
                val result = repository.attendees(eventId, query, cursor); currentCoroutineContext().ensureActive()
                if (generation != owner || serial != request || state.value.dashboard?.revision != dashboard.revision) return@launch
                when (result) {
                    is AttendeesResult.Failed -> if (!denied(result.error)) {
                        mutable.value = state.value.copy(pageError = result.error)
                        if (result.error == AttendanceError.STALE_PAGE) refresh()
                    }
                    is AttendeesResult.Page -> if (result.revision != dashboard.revision) refresh() else {
                        loadedRevision = result.revision
                        mutable.value = state.value.copy(rows = if (append) state.value.rows + result.rows else result.rows,
                            matches = result.totalMatches, next = result.next)
                    }
                }
            } finally { if (generation == owner && serial == request) mutable.value = state.value.copy(paging = false) }
        }
    }
    fun refresh() {
        val eventId = id ?: return; if (!active) return
        if (state.value.refreshing) { refreshPending = true; return }
        val generation = owner; val startedObservation = observation
        mutable.value = state.value.copy(refreshing = true)
        // Publish the job before an immediate/cached reply can start a queued follow-up.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.refreshDashboard(eventId); currentCoroutineContext().ensureActive()
                // Revisions are opaque: any intervening observation supersedes this read's result.
                if (generation == owner && observation == startedObservation) accept(result)
            } finally { if (generation == owner) {
                mutable.value = state.value.copy(refreshing = false)
                val followUp = refreshPending; refreshPending = false
                if (followUp && currentCoroutineContext().isActive) refresh()
            } }
        }
        refresh = job; job.start()
    }
    fun confirm(row: Attendee?) { if (!state.value.cancelling) mutable.value = state.value.copy(selected = row, cancelError = null) }
    fun cancel() {
        val target = state.value.selected ?: return; val eventId = id ?: return
        if (!active || state.value.cancelling) return; val generation = owner
        mutable.value = state.value.copy(cancelling = true, cancelError = null)
        command = viewModelScope.launch {
            try {
                val result = repository.cancelRegistration(eventId, target.registrationId); currentCoroutineContext().ensureActive()
                if (generation != owner) return@launch
                when (result) {
                    OwnerCancelResult.Cancelled -> mutable.value = state.value.copy(selected = null)
                    is OwnerCancelResult.Failed -> if (!denied(result.error)) mutable.value = state.value.copy(cancelError = result.error)
                }
            } finally { if (generation == owner) mutable.value = state.value.copy(cancelling = false) }
        }
    }
}
