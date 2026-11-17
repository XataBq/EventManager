package ru.eventpass.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.eventpass.core.domain.profile.*

data class ProfileUiState(val profile:Profile?=null,val fields:ProfileFields=ProfileFields(),val dirty:Boolean=false,
    val loading:Boolean=true,val busy:Boolean=false,val refreshing:Boolean=false,val readError:ProfileError?=null,
    val conflict:Boolean=false,val error:ProfileError?=null,val saved:Boolean=false,val confirming:Boolean=false,val exit:Boolean=false,
    val deleteDialog:Boolean=false,val deleting:Boolean=false,val deleteUncertain:Boolean=false,val deletePresent:Boolean=false,
    val deleteError:ProfileError?=null,val deleted:Boolean=false)

@HiltViewModel
class ProfileViewModel @Inject constructor(private val repository:ProfileRepository):ViewModel() {
    private val mutable=MutableStateFlow(ProfileUiState());val state=mutable.asStateFlow()
    private var owner=0L;private var observerOwner=0L;private var readOwner=0L;private var editRevision=0L
    private var observer:Job?=null;private var command:Job?=null;private var read:Job?=null
    private var observation=0L;private var active=false
    fun start() {
        if(active) return
        active=true;val generation=owner;val observing=++observerOwner
        observer=viewModelScope.launch {repository.observeProfile().collect {result->
            if(generation==owner && observing==observerOwner && active) {observation++;accept(result)}
        }}
    }
    private fun accept(result:ProfileResult) {
        when(result) {
            is ProfileResult.Failed->failure(result.error,true)
            is ProfileResult.Ready->{
                val old=state.value
                if(old.profile!=null && old.profile.account.id!=result.profile.account.id) {failure(ProfileError.SESSION_CHANGED);return}
                val fields=if(old.dirty || old.busy) old.fields else result.profile.fields
                val dirty=fields!=result.profile.fields
                mutable.value=old.copy(profile=result.profile,fields=fields,dirty=dirty,loading=false,readError=null,
                    deleteUncertain=old.deleteUncertain || result.deletionUncertain,
                    conflict=dirty && (old.conflict || old.dirty && !old.busy && old.profile?.fields!=result.profile.fields))
            }
        }
    }
    private fun failure(error:ProfileError,reading:Boolean=false) {
        if(error in setOf(ProfileError.AUTHENTICATION_REQUIRED,ProfileError.SESSION_CHANGED,ProfileError.FORBIDDEN)) {
            stop();mutable.value=ProfileUiState(loading=false,error=error)
        } else if(error==ProfileError.DELETE_UNCONFIRMED) mutable.value=state.value.copy(deleteUncertain=true,deleteDialog=true,deleteError=error)
        else mutable.value=if(reading) state.value.copy(loading=false,readError=error) else state.value.copy(error=error)
    }
    fun refresh() {
        if(!active || read?.isActive==true || state.value.busy || state.value.confirming || state.value.deleteDialog) return
        val generation=owner;val token=++readOwner;val started=observation
        mutable.value=state.value.copy(refreshing=!state.value.loading,readError=null)
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=repository.refreshProfile();currentCoroutineContext().ensureActive()
                if(generation==owner && token==readOwner && observation==started) accept(result)
            } finally {if(generation==owner && token==readOwner) mutable.value=state.value.copy(loading=false,refreshing=false)}
        }
        read=job;job.start()
    }
    fun pause(recreating:Boolean) {
        active=false;observerOwner++;readOwner++;observer?.cancel();read?.cancel()
        if(!recreating) {owner++;command?.cancel()}
        mutable.value=state.value.copy(refreshing=false,busy=if(recreating) state.value.busy else false,deleting=if(recreating) state.value.deleting else false)
    }
    fun stop() {owner++;observerOwner++;readOwner++;active=false;observer?.cancel();command?.cancel();read?.cancel();mutable.value=ProfileUiState()}
    fun edit(fields:ProfileFields) {
        val old=state.value;if(old.profile==null || old.deleting || old.deleteUncertain || old.deleteDialog) return
        editRevision++
        mutable.value=old.copy(fields=fields,dirty=fields!=old.profile.fields,error=null,saved=false,conflict=old.conflict && fields!=old.profile.fields)
    }
    fun requestLeave():Boolean {if(state.value.busy) return false
        if(!state.value.dirty) return true
        mutable.value=state.value.copy(confirming=true);return false
    }
    fun keepEditing() {if(!state.value.busy) mutable.value=state.value.copy(confirming=false)}
    fun discard() {val s=state.value;if(s.busy) return;editRevision++;mutable.value=s.copy(fields=s.profile?.fields ?: ProfileFields(),dirty=false,confirming=false,exit=true,error=null,conflict=false)}
    fun consumeExit() {mutable.value=state.value.copy(exit=false)}
    fun save(exit:Boolean=false) {
        val s=state.value;if(s.busy || s.profile==null || s.deleteUncertain) return
        if(!ProfilePolicy.valid(s.fields)) {mutable.value=s.copy(error=ProfileError.INVALID_INPUT);return}
        observation++;val generation=owner;val sentRevision=editRevision;mutable.value=s.copy(busy=true,error=null,saved=false)
        command=viewModelScope.launch {
            try {
                val result=repository.saveProfile(s.fields);currentCoroutineContext().ensureActive()
                if(generation!=owner) return@launch
                when(result) {
                    is ProfileResult.Failed->failure(result.error)
                    is ProfileResult.Ready->{
                        val fields=if(editRevision==sentRevision) result.profile.fields else state.value.fields
                        val dirty=fields!=result.profile.fields
                        mutable.value=state.value.copy(profile=result.profile,fields=fields,dirty=dirty,saved=!dirty,
                            confirming=exit && dirty,exit=exit && !dirty,conflict=false)
                    }
                }
            } finally {if(generation==owner) mutable.value=state.value.copy(busy=false)}
        }
    }
    fun confirmDelete() {if(!state.value.busy) mutable.value=state.value.copy(deleteDialog=true,deleteError=null,confirming=false)}
    fun dismissDelete() {if(!state.value.busy) mutable.value=state.value.copy(deleteDialog=false)}
    fun deleteAccount() {if(!state.value.deleteUncertain) deleteOperation(false)}
    fun checkDeletion()=deleteOperation(true)
    private fun deleteOperation(check:Boolean) {
        val s=state.value;if(s.busy || s.profile==null || !s.deleteDialog) return
        val generation=owner;observation++
        mutable.value=s.copy(busy=true,deleting=true,deleteUncertain=true,deleteError=null,deletePresent=false)
        command=viewModelScope.launch {
            try {
                val result=if(check) repository.reconcileDeletion() else repository.deleteAccount()
                currentCoroutineContext().ensureActive();if(generation!=owner) return@launch
                when(result) {
                    is DeleteAccountResult.Deleted->mutable.value=state.value.copy(deleted=true,deleteDialog=false,deleteUncertain=false,deleteError=result.localCleanupError)
                    is DeleteAccountResult.Present->{
                        accept(ProfileResult.Ready(result.profile))
                        mutable.value=state.value.copy(deleteUncertain=false,deletePresent=true,deleteError=null)
                    }
                    is DeleteAccountResult.Uncertain->mutable.value=state.value.copy(deleteUncertain=true,deleteError=result.error)
                    DeleteAccountResult.AccessRevoked->failure(ProfileError.AUTHENTICATION_REQUIRED)
                    is DeleteAccountResult.Failed->mutable.value=state.value.copy(deleteUncertain=result.error==ProfileError.DELETE_UNCONFIRMED,deleteError=result.error)
                }
            } finally {if(generation==owner) mutable.value=state.value.copy(busy=false,deleting=false)}
        }
    }
}
