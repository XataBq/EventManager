package ru.eventpass.core.domain.checkin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.eventpass.core.domain.event.EventId

enum class ScannerPermission { NOT_REQUESTED, GRANTED, DENIED, PERMANENTLY_DENIED }
enum class ScannerPhase { ACCESS_LOADING, READY, PERMISSION_REQUIRED, SCANNING, SUBMITTING, RESULT, ERROR, ACCESS_DENIED }
data class ScannerState(val phase: ScannerPhase = ScannerPhase.ACCESS_LOADING,
    val access: ScannerAccess.Allowed? = null, val permission: ScannerPermission = ScannerPermission.NOT_REQUESTED,
    val foreground: Boolean = false, val generation: Long = 0, val result: CheckInOutcome? = null, val error: CheckInError? = null) {
    val cameraActive: Boolean get() = foreground && access != null && permission == ScannerPermission.GRANTED && phase == ScannerPhase.SCANNING
}

/** Pure entry-owned model, not Android camera wiring. All methods run on the caller's one UI dispatcher. */
class ScannerController(private val repository: CheckInRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ScannerState()); val state = mutable.asStateFlow()
    private var observation: Job? = null; private var operation: Job? = null
    private var eventId: EventId? = null
    private var generation = 0L

    fun bind(id: EventId) {
        if (eventId == id) return
        close(); eventId = id; mutable.value = ScannerState(generation = generation)
        observation = scope.launch { repository.observeAccess(id).collect { access ->
            val old = mutable.value
            when (access) {
                is ScannerAccess.Denied -> {
                    cancelOperation()
                    mutable.value = ScannerState(ScannerPhase.ACCESS_DENIED, permission = old.permission,
                        foreground = old.foreground, generation = generation, error = access.error)
                }
                is ScannerAccess.Allowed -> {
                    if (access.eventId != id) {
                        cancelOperation()
                        mutable.value = ScannerState(ScannerPhase.ACCESS_DENIED, foreground = old.foreground, generation = generation, error = CheckInError.NOT_FOUND)
                    } else if (old.access?.scope != access.scope || old.access.eventId != id) {
                        cancelOperation()
                        mutable.value = ScannerState(ScannerPhase.READY, access, old.permission, old.foreground, generation)
                    } else mutable.value = old.copy(access = access)
                }
            }
        } }
    }
    fun foreground(value: Boolean) {
        if (mutable.value.foreground == value) return
        cancelOperation()
        mutable.value = mutable.value.copy(foreground = value, generation = generation,
            phase = if (mutable.value.access == null) mutable.value.phase else ScannerPhase.READY, result = null,
            error = if (mutable.value.access == null) mutable.value.error else null)
    }
    fun requestCamera() {
        val s = mutable.value
        if (s.access == null || !s.foreground || s.phase !in setOf(ScannerPhase.READY, ScannerPhase.ERROR)) return
        mutable.value = s.copy(phase = if (s.permission == ScannerPermission.GRANTED) ScannerPhase.SCANNING else ScannerPhase.PERMISSION_REQUIRED, error = null)
    }
    fun permission(value: ScannerPermission) {
        val s = mutable.value
        mutable.value = s.copy(permission = value, phase = when {
            s.phase == ScannerPhase.PERMISSION_REQUIRED && value == ScannerPermission.GRANTED && s.foreground && s.access != null -> ScannerPhase.SCANNING
            s.phase == ScannerPhase.PERMISSION_REQUIRED || (s.phase == ScannerPhase.SCANNING && value != ScannerPermission.GRANTED) -> ScannerPhase.READY
            else -> s.phase
        })
    }
    /** Empty/non-decoded frames do nothing. One deterministic candidate per camera cycle. */
    fun frame(cameraGeneration: Long, candidates: List<String>) {
        val s = mutable.value
        if (!s.cameraActive || cameraGeneration != generation) return
        val raw = candidates.filter(String::isNotBlank).minOrNull() ?: return
        submit(TicketCredential.Qr(QrToken(raw)))
    }
    fun manual(raw: String) {
        if (!canSubmit()) return
        val code = ManualCodePolicy.parse(raw)
        if (code == null) { mutable.value = mutable.value.copy(phase = ScannerPhase.ERROR, error = CheckInError.INVALID_INPUT); return }
        submit(TicketCredential.Manual(code))
    }
    private fun canSubmit() = mutable.value.let { it.foreground && it.access != null && it.phase in setOf(ScannerPhase.READY, ScannerPhase.SCANNING, ScannerPhase.ERROR) }
    private fun submit(credential: TicketCredential) {
        if (!canSubmit()) return
        val access = mutable.value.access!!; val id = eventId ?: return; val token = generation
        mutable.value = mutable.value.copy(phase = ScannerPhase.SUBMITTING, result = null, error = null)
        operation = scope.launch {
            try {
                val result = repository.checkIn(id, credential)
                currentCoroutineContext().ensureActive()
                // A delayed call cannot show PII under a different/revoked session, even before its observer runs.
                val latest = repository.observeAccess(id).first()
                currentCoroutineContext().ensureActive()
                if (generation != token || !mutable.value.foreground) return@launch
                if (latest !is ScannerAccess.Allowed || latest.scope != access.scope) {
                    cancelOperation()
                    mutable.value = ScannerState(ScannerPhase.ACCESS_DENIED, permission = mutable.value.permission,
                        foreground = mutable.value.foreground, generation = generation,
                        error = (latest as? ScannerAccess.Denied)?.error ?: CheckInError.SESSION_CHANGED)
                    return@launch
                }
                mutable.value = when {
                    result is CheckInOutcome.Failed && result.error in setOf(CheckInError.AUTHENTICATION_REQUIRED, CheckInError.FORBIDDEN, CheckInError.SESSION_CHANGED, CheckInError.NOT_FOUND) ->
                        mutable.value.copy(phase = ScannerPhase.ACCESS_DENIED, access = null, result = null, error = result.error)
                    result is CheckInOutcome.Failed -> mutable.value.copy(phase = ScannerPhase.ERROR, error = result.error)
                    else -> mutable.value.copy(phase = ScannerPhase.RESULT, result = result)
                }
            } finally {
                if (generation == token && mutable.value.phase == ScannerPhase.SUBMITTING)
                    mutable.value = mutable.value.copy(phase = ScannerPhase.READY)
            }
        }
    }
    fun next() {
        if (mutable.value.phase !in setOf(ScannerPhase.RESULT, ScannerPhase.ERROR)) return
        generation++
        mutable.value = mutable.value.copy(phase = if (mutable.value.foreground && mutable.value.permission == ScannerPermission.GRANTED) ScannerPhase.SCANNING else ScannerPhase.READY,
            generation = generation, result = null, error = null)
    }
    /** Result Back stays in the scanner; scanner Back disposes this owner in a future route. */
    fun backFromResult() {
        if (mutable.value.phase != ScannerPhase.RESULT) return
        generation++; mutable.value = mutable.value.copy(phase = ScannerPhase.READY, result = null, error = null, generation = generation)
    }
    fun close() { cancelOperation(); observation?.cancel(); eventId = null; mutable.value = ScannerState(generation = generation) }
    private fun cancelOperation() { generation++; operation?.cancel(); operation = null }
}
