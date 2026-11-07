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
    val refreshing: Boolean = false, val readError: ProfileError? = null, val conflict: Boolean = false,
    val error: ProfileError? = null, val saved: Boolean = false, val confirming: Boolean = false, val exit: Boolean = false)
@HiltViewModel
class ProfileViewModel @Inject constructor(private val repository: ProfileRepository) : ViewModel() {
    private val mutable=MutableStateFlow(ProfileUiState()); val state=mutable.asStateFlow()
    private var owner=0L; private var observer: Job?=null; private var command: Job?=null
    private var read: Job?=null; private var observation=0L; private var active=false
    fun start() {
        stop(); active=true; val generation=owner
        observer=viewModelScope.launch { repository.observeProfile().collect { result -> if(generation==owner) {
            observation++; accept(result)
        } } }
    }
    private fun accept(result: ProfileResult) {
        when(result) {
            is ProfileResult.Ready -> {
                val old=state.value; val fields=if(old.dirty) old.fields else result.profile.fields
                if(old.profile!=null && old.profile.account.id!=result.profile.account.id) { failure(ProfileError.SESSION_CHANGED); return }
                val dirty=fields!=result.profile.fields
                mutable.value=old.copy(profile=result.profile,fields=fields,dirty=dirty,loading=false,readError=null,
                    conflict=dirty && (old.conflict || (old.dirty && old.profile?.fields!=result.profile.fields)))
            }
            is ProfileResult.Failed -> failure(result.error,reading=true)
        }
    }
    private fun failure(error: ProfileError, reading: Boolean = false) {
        if(error in setOf(ProfileError.AUTHENTICATION_REQUIRED,ProfileError.SESSION_CHANGED)) {
            stop(); mutable.value=state.value.copy(loading=false,error=error)
        } else mutable.value=if(reading) state.value.copy(loading=false,readError=error) else state.value.copy(error=error)
    }
    fun refresh() {
        if(!active || read?.isActive==true || state.value.busy || state.value.confirming) return
        val generation=owner; val started=observation
        mutable.value=state.value.copy(refreshing=!state.value.loading,readError=null)
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try {val result=repository.refreshProfile(); currentCoroutineContext().ensureActive()
                if(generation==owner && observation==started) accept(result)
            } finally {if(generation==owner) mutable.value=state.value.copy(loading=false,refreshing=false)}
        }
        read=job; job.start()
    }
    fun stop() { active=false; owner++; observation=0; observer?.cancel(); command?.cancel(); read?.cancel(); mutable.value=ProfileUiState() }
    fun edit(fields: ProfileFields) { val s=state.value; if(s.busy || s.profile==null) return
        mutable.value=s.copy(fields=fields,dirty=fields!=s.profile.fields,error=null,saved=false,conflict=s.conflict && fields!=s.profile.fields) }
    fun requestLeave(): Boolean { if(state.value.busy) return false
        if(!state.value.dirty) return true; mutable.value=state.value.copy(confirming=true); return false }
    fun keepEditing() { if(!state.value.busy) mutable.value=state.value.copy(confirming=false) }
    fun discard() { val s=state.value; if(s.busy) return; mutable.value=s.copy(fields=s.profile?.fields ?: ProfileFields(),dirty=false,confirming=false,exit=true,error=null,conflict=false) }
    fun consumeExit() { mutable.value=state.value.copy(exit=false) }
    fun save(exit: Boolean = false) {
        val s=state.value; if(s.busy || s.profile==null) return
        if(!ProfilePolicy.valid(s.fields)) { mutable.value=s.copy(error=ProfileError.INVALID_INPUT); return }
        observation++; val generation=owner; mutable.value=s.copy(busy=true,error=null,saved=false)
        command=viewModelScope.launch {
            try { val result=repository.saveProfile(s.fields); currentCoroutineContext().ensureActive()
                if(generation==owner) when(result) {
                    is ProfileResult.Ready -> mutable.value=state.value.copy(profile=result.profile,fields=result.profile.fields,dirty=false,saved=true,confirming=false,exit=exit,conflict=false)
                    is ProfileResult.Failed -> failure(result.error)
                }
            } finally { if(generation==owner) mutable.value=state.value.copy(busy=false) }
        }
    }
}
