package ru.eventpass.app.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.TicketId

data class DocumentPicker(val id: String, val request: DocumentRequest, val launched: Boolean = false)
data class DocumentsUiState(val busy: Boolean = false, val picker: DocumentPicker? = null,
    val error: DocumentError? = null, val saved: Boolean = false, val mailQueued: Boolean = false, val retryAt: Instant? = null)
@HiltViewModel
class DocumentsViewModel @Inject constructor(private val repository: DocumentsRepository, private val files: DocumentFiles,
    private val auth: AuthRepository) : ViewModel() {
    private val mutable=MutableStateFlow(DocumentsUiState()); val state=mutable.asStateFlow()
    private var owner=0L; private var job: Job?=null; private var session: SessionSnapshot?=null
    private var resource: DocumentRequest?=null
    init { viewModelScope.launch { auth.observeSession().collect { if(it==auth.currentSession() && session!=null && it!=session) clear() } } }
    fun clear() { owner++; job?.cancel(); session=null; resource=null; mutable.value=DocumentsUiState() }
    fun bind(request: DocumentRequest, visible: Boolean) {
        if(!visible || (resource!=null && resource!=request)) clear()
        if(visible) resource=request
    }
    fun unbind(recreating: Boolean) { if(!recreating) clear() }
    fun begin(request: DocumentRequest) {
        if(state.value.busy) return
        val current=auth.currentSession(); if(current.state !is SessionState.Authenticated) { mutable.value=DocumentsUiState(error=DocumentError.AUTHENTICATION_REQUIRED); return }
        resource=request; session=current; mutable.value=DocumentsUiState(busy=true,picker=DocumentPicker(UUID.randomUUID().toString(),request))
    }
    fun launched(id: String) { state.value.picker?.takeIf { it.id==id }?.let { mutable.value=state.value.copy(picker=it.copy(launched=true)) } }
    fun pickerFailed(id: String) { if(state.value.picker?.id==id) mutable.value=DocumentsUiState(error=DocumentError.PROVIDER_UNAVAILABLE) }
    fun destination(id: String?, uri: String?) {
        val pending=state.value.picker?.takeIf { it.id==id } ?: return
        if(uri==null) { mutable.value=DocumentsUiState(); return }
        val expected=session; val generation=owner; mutable.value=state.value.copy(picker=null)
        job=viewModelScope.launch {
            try {
                if(expected!=auth.currentSession()) return@launch
                val result=repository.prepare(pending.request); currentCoroutineContext().ensureActive()
                if(generation!=owner) return@launch
                val error=when(result) {
                    is DocumentResult.Failed -> result.error
                    is DocumentResult.Ready -> files.save(result.snapshot,uri) {
                        if(expected!=auth.currentSession()) DocumentError.SESSION_CHANGED else repository.validate(result.snapshot)
                    }
                }
                currentCoroutineContext().ensureActive()
                if(generation==owner && expected==auth.currentSession()) mutable.value=state.value.copy(error=error,saved=error==null)
            } finally { if(generation==owner) mutable.value=state.value.copy(busy=false) }
        }
    }
    fun resend(id: TicketId) {
        if(state.value.busy) return
        val generation=owner; resource=DocumentRequest.TicketPdf(id); session=auth.currentSession(); mutable.value=DocumentsUiState(busy=true)
        job=viewModelScope.launch {
            try { val result=repository.resendTicket(id); currentCoroutineContext().ensureActive()
                if(generation==owner && session==auth.currentSession()) mutable.value=when(result) {
                    is ResendResult.Queued -> DocumentsUiState(mailQueued=true,retryAt=result.retryAt)
                    is ResendResult.Cooldown -> DocumentsUiState(retryAt=result.retryAt)
                    is ResendResult.Failed -> DocumentsUiState(error=result.error)
                }
            } finally { if(generation==owner) mutable.value=state.value.copy(busy=false) }
        }
    }
}
