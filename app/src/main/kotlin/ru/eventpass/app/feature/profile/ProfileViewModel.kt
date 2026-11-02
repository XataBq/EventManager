package ru.eventpass.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eventpass.core.domain.profile.*

data class ProfileUiState(val profile: Profile? = null, val fields: ProfileFields = ProfileFields(),
    val dirty: Boolean = false, val loading: Boolean = true, val busy: Boolean = false,
    val error: ProfileError? = null, val saved: Boolean = false, val confirming: Boolean = false, val exit: Boolean = false)
@HiltViewModel
class ProfileViewModel @Inject constructor(private val repository: ProfileRepository) : ViewModel() {
    private val mutable=MutableStateFlow(ProfileUiState()); val state=mutable.asStateFlow()
    private var owner=0L; private var observer: Job?=null; private var command: Job?=null
    fun start() {
        stop(); val generation=owner
        observer=viewModelScope.launch { repository.observeProfile().collect { result -> if(generation==owner) when(result) {
            is ProfileResult.Ready -> {
                val old=state.value; val fields=if(old.dirty) old.fields else result.profile.fields
                mutable.value=old.copy(profile=result.profile,fields=fields,dirty=fields!=result.profile.fields,loading=false)
            }
            is ProfileResult.Failed -> {
                if(result.error in setOf(ProfileError.AUTHENTICATION_REQUIRED,ProfileError.SESSION_CHANGED)) {
                    stop(); mutable.value=state.value.copy(loading=false,error=result.error)
                } else mutable.value=state.value.copy(loading=false,error=result.error)
            }
        } } }
    }
    fun stop() { owner++; observer?.cancel(); command?.cancel(); mutable.value=ProfileUiState() }
    fun edit(fields: ProfileFields) { val s=state.value; if(s.busy || s.profile==null) return
        mutable.value=s.copy(fields=fields,dirty=fields!=s.profile.fields,error=null,saved=false) }
    fun requestLeave(): Boolean { if(state.value.busy) return false
        if(!state.value.dirty) return true; mutable.value=state.value.copy(confirming=true); return false }
    fun keepEditing() { if(!state.value.busy) mutable.value=state.value.copy(confirming=false) }
    fun discard() { val s=state.value; if(s.busy) return; mutable.value=s.copy(fields=s.profile?.fields ?: ProfileFields(),dirty=false,confirming=false,exit=true,error=null) }
    fun consumeExit() { mutable.value=state.value.copy(exit=false) }
    fun save(exit: Boolean = false) {
        val s=state.value; if(s.busy || s.profile==null) return
        if(!ProfilePolicy.valid(s.fields)) { mutable.value=s.copy(error=ProfileError.INVALID_INPUT); return }
        val generation=owner; mutable.value=s.copy(busy=true,error=null,saved=false)
        command=viewModelScope.launch {
            try { val result=repository.saveProfile(s.fields); currentCoroutineContext().ensureActive()
                if(generation==owner) when(result) {
                    is ProfileResult.Ready -> mutable.value=state.value.copy(profile=result.profile,fields=result.profile.fields,dirty=false,saved=true,confirming=false,exit=exit)
                    is ProfileResult.Failed -> mutable.value=state.value.copy(error=result.error)
                }
            } finally { if(generation==owner) mutable.value=state.value.copy(busy=false) }
        }
    }
}
