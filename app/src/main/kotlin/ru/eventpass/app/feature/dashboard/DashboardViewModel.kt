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
    val cancelling: Boolean = false, val cancelError: AttendanceError? = null,val matchesKnown:Boolean=false,
    val detail:Attendee?=null,val panel:Int=0,val analytics:QuestionAnalytics?=null,
    val analyticsLoading:Boolean=false,val analyticsError:AttendanceError?=null)

@HiltViewModel
class DashboardViewModel @Inject constructor(private val repository: AttendanceRepository, saved: SavedStateHandle) : ViewModel() {
    // A search can contain names/email: keep it in the live entry VM, never persist it with attendee PII.
    private val mutable=MutableStateFlow(DashboardUiState());val state=mutable.asStateFlow()
    init {saved.remove<String>("query")}
    private var id:EventId?=null;private var owner=0L;private var request=0L;private var selection=0L;private var readOwner=0L
    private var observer:Job?=null;private var page:Job?=null;private var command:Job?=null;private var readJob:Job?=null
    private var loadedRevision:String?=null;private var active=false;private var observation=0L
    private var reading=false;private var refreshPending=false;private var recoveries=1
    private var analyticsJob:Job?=null;private var analyticsRequest=0L
    fun start(eventId:String) {
        val same=id?.value==eventId
        stop(clearQuery=!same);id=EventId(eventId);active=true;observe()
        if(state.value.panel==1) loadAnalytics()
    }
    fun stop(clearQuery:Boolean=false) {
        val query=if(clearQuery) "" else state.value.query
        val panel=if(clearQuery) 0 else state.value.panel
        active=false;owner++;request++;selection++;readOwner++
        observer?.cancel();page?.cancel();command?.cancel();readJob?.cancel();analyticsJob?.cancel();analyticsRequest++
        loadedRevision=null;observation=0;reading=false;refreshPending=false;recoveries=1
        mutable.value=DashboardUiState(query=query,panel=panel)
    }
    private fun observe() {
        val event=id ?: return;val generation=owner;val selected=++selection;val query=state.value.query
        observer?.cancel()
        observer=viewModelScope.launch {repository.observeDashboard(event,query).collect {result->
            if(generation==owner && selected==selection) {observation++;accept(result)}
        }}
    }
    private fun denied(error:AttendanceError):Boolean {
        if(error !in setOf(AttendanceError.AUTHENTICATION_REQUIRED,AttendanceError.FORBIDDEN,AttendanceError.NOT_FOUND,AttendanceError.SESSION_CHANGED)) return false
        stop(clearQuery=true);mutable.value=DashboardUiState(loading=false,denied=true,readError=error);return true
    }
    private fun accept(result:DashboardResult,refreshed:Boolean=false) {
        when(result) {
            is DashboardResult.Failed->if(!denied(result.error)) mutable.value=state.value.copy(loading=false,readError=result.error)
            is DashboardResult.Ready->{
                val first=result.firstPage
                if(first!=null) {
                    if(first.query!=state.value.query.trim()) return
                    if(first.revision!=result.dashboard.revision) {stale();return}
                    request++;page?.cancel();loadedRevision=first.revision
                    val selected=if(state.value.cancelling) state.value.selected else state.value.selected?.let {old->first.rows.firstOrNull {it.ticketId==old.ticketId}}
                    mutable.value=state.value.copy(dashboard=result.dashboard,rows=first.rows,matches=first.totalMatches,next=first.next,
                        matchesKnown=true,loading=false,paging=false,readError=null,pageError=null,selected=selected)
                } else {
                    // Legacy adapter: clear the old chain before showing a different revision's totals.
                    val changed=state.value.dashboard?.revision!=result.dashboard.revision
                    if(changed) {request++;page?.cancel();loadedRevision=null;mutable.value=state.value.copy(rows=emptyList(),next=null,matches=0,matchesKnown=false,paging=false)}
                    mutable.value=state.value.copy(dashboard=result.dashboard,loading=false,readError=null)
                    if(changed || loadedRevision==null && !state.value.paging) load(false)
                    else if(refreshed) load(false,keepRows=true)
                }
            }
        }
    }
    fun query(value:String) {
        if(state.value.cancelling) return
        val text=if(value.codePointCount(0,value.length)>300) value.substring(0,value.offsetByCodePoints(0,300)) else value
        if(text==state.value.query) return
        request++;readOwner++;page?.cancel();readJob?.cancel();reading=false;refreshPending=false;loadedRevision=null;recoveries=1
        mutable.value=state.value.copy(query=text,detail=null,rows=emptyList(),matches=0,matchesKnown=false,next=null,selected=null,pageError=null,paging=false,refreshing=false)
        if(active) {observe();requestRefresh(false)}
    }
    fun more() {if(!state.value.paging && state.value.next!=null) load(true)}
    fun retryPage() {if(state.value.next!=null && state.value.rows.isNotEmpty()) load(true) else refresh()}
    private fun stale() {
        loadedRevision=null
        mutable.value=state.value.copy(rows=emptyList(),matches=0,matchesKnown=false,next=null,pageError=AttendanceError.STALE_PAGE)
        if(recoveries>0) {recoveries--;requestRefresh(true)}
    }
    private fun load(append:Boolean,keepRows:Boolean=false) {
        val event=id ?: return;val dashboard=state.value.dashboard ?: return;if(!active) return
        page?.cancel();val serial=++request;val generation=owner;val query=state.value.query
        val cursor=if(append) state.value.next ?: return else null
        if(!append && !keepRows) {loadedRevision=null;mutable.value=state.value.copy(rows=emptyList(),next=null,matches=0,matchesKnown=false)}
        mutable.value=state.value.copy(paging=true,pageError=null)
        page=viewModelScope.launch {
            try {
                val result=repository.attendees(event,query,cursor);currentCoroutineContext().ensureActive()
                if(generation!=owner || serial!=request || state.value.query!=query || state.value.dashboard?.revision!=dashboard.revision) return@launch
                when(result) {
                    is AttendeesResult.Failed->if(!denied(result.error)) {
                        mutable.value=state.value.copy(pageError=result.error)
                        if(result.error==AttendanceError.STALE_PAGE) stale()
                    }
                    is AttendeesResult.Page->if(result.revision!=dashboard.revision) stale() else {
                        val rows=if(append) state.value.rows+result.rows else result.rows
                        if(rows.map {it.ticketId}.distinct().size!=rows.size) {mutable.value=state.value.copy(pageError=AttendanceError.PROTOCOL);return@launch}
                        loadedRevision=result.revision
                        mutable.value=state.value.copy(rows=rows,matches=result.totalMatches,matchesKnown=true,next=result.next)
                    }
                }
            } finally {if(generation==owner && serial==request) mutable.value=state.value.copy(paging=false)}
        }
    }
    fun refresh() {recoveries=1;requestRefresh(false)}
    fun refreshVisible() {if(state.value.panel==1 && state.value.dashboard!=null) loadAnalytics() else refresh()}
    fun panel(value:Int) {
        if(value !in 0..1 || state.value.cancelling || value==state.value.panel) return
        analyticsJob?.cancel();analyticsRequest++
        mutable.value=state.value.copy(panel=value,detail=null,selected=null,analyticsLoading=false)
        if(value==1) loadAnalytics()
    }
    private fun loadAnalytics() {
        val event=id ?: return
        if(!active || analyticsJob?.isActive==true) return
        val generation=owner;val token=++analyticsRequest
        mutable.value=state.value.copy(analyticsLoading=true,analyticsError=null)
        analyticsJob=viewModelScope.launch {
            try {
                val result=repository.questionAnalytics(event);currentCoroutineContext().ensureActive()
                if(generation!=owner || token!=analyticsRequest || state.value.panel!=1) return@launch
                when(result) {
                    is QuestionAnalyticsResult.Ready -> mutable.value=state.value.copy(analytics=result.analytics,analyticsError=null)
                    is QuestionAnalyticsResult.Failed -> if(!denied(result.error)) mutable.value=state.value.copy(analyticsError=result.error)
                }
            } finally {if(generation==owner && token==analyticsRequest) mutable.value=state.value.copy(analyticsLoading=false)}
        }
    }
    private fun requestRefresh(followUp:Boolean) {
        val event=id ?: return;if(!active) return
        if(reading) {if(followUp) refreshPending=true;return}
        val generation=owner;val selected=selection;val token=++readOwner;val observed=observation;val query=state.value.query
        reading=true;mutable.value=state.value.copy(refreshing=!state.value.loading)
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=repository.refreshDashboard(event,query);currentCoroutineContext().ensureActive()
                // An intervening observer wins; opaque revisions are never ordered or compared as timestamps.
                if(generation==owner && selected==selection && token==readOwner && observation==observed) {accept(result,true);page?.join()}
            } finally {if(generation==owner && token==readOwner) {
                reading=false;mutable.value=state.value.copy(refreshing=false)
                val again=refreshPending;refreshPending=false
                if(again && currentCoroutineContext().isActive) requestRefresh(false)
            }}
        }
        readJob=job;job.start()
    }
    fun confirm(row:Attendee?) {if(!state.value.cancelling) mutable.value=state.value.copy(selected=row,cancelError=null)}
    fun details(row:Attendee?) {if(!state.value.cancelling && state.value.selected==null) mutable.value=state.value.copy(detail=row)}
    fun cancel() {
        val target=state.value.selected ?: return;val event=id ?: return
        if(!active || state.value.cancelling) return;val generation=owner
        analyticsJob?.cancel();analyticsRequest++
        mutable.value=state.value.copy(cancelling=true,cancelError=null,analytics=null,analyticsLoading=false)
        command=viewModelScope.launch {
            try {
                val result=repository.cancelTicket(event,target.ticketId);currentCoroutineContext().ensureActive()
                if(generation!=owner) return@launch
                when(result) {
                    OwnerCancelResult.Cancelled->{mutable.value=state.value.copy(selected=null,detail=null);recoveries=1;requestRefresh(true)}
                    is OwnerCancelResult.Failed->if(!denied(result.error)) mutable.value=state.value.copy(cancelError=result.error)
                }
            } finally {if(generation==owner) mutable.value=state.value.copy(cancelling=false)}
        }
    }
}
