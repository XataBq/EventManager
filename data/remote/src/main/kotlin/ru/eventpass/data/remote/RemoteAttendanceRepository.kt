@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.eventpass.data.remote

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.auth.AuthError
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

/** One coherent stats/first-page projection per actor/event/query, with bounded stale recovery. */
class RemoteAttendanceRepository(private val api:AuthHttp,private val auth:RemoteAuthRepository,
    private val organizer:RemoteOrganizerRepository,private val volunteers:VolunteerRepository,
    private val tickets:RemoteTickets,private val scanner:RemoteCheckInRepository,private val clock:Clock=Clock.systemUTC()):AttendanceRepository {
    private data class Key(val event:EventId,val query:String)
    private data class Stamped(val context:PublicReadContext,val result:DashboardResult)
    private class Entry {
        val lock=Mutex();val completed=AtomicLong();val version=AtomicLong();val state=MutableStateFlow<Stamped?>(null)
        var pins=0 // Protected by entries; includes observers and in-flight reads.
    }
    private class Failure(val error:AttendanceError):Exception("Attendance operation failed")
    private val entries=linkedMapOf<Key,Entry>()
    private val writes=java.util.concurrent.ConcurrentHashMap<EventId,Mutex>()
    private var lastContext:PublicReadContext?=null
    init {auth.onPrivateSessionClosed {synchronized(entries) {entries.values.forEach {it.version.incrementAndGet();it.state.value=null};entries.clear();lastContext=null}}}
    private fun acquire(key:Key,c:PublicReadContext):Entry=synchronized(entries) {
        if(lastContext?.let {!auth.isCurrentPublicRead(it)}==true) {
            entries.values.forEach {it.version.incrementAndGet();it.state.value=null};entries.clear()
        }
        lastContext=c
        val entry=entries.remove(key) ?: Entry()
        entry.pins++;entries[key]=entry;trim()
        entry
    }
    private fun trim() {
        val iterator=entries.iterator()
        while(entries.size>32 && iterator.hasNext()) {
            val item=iterator.next()
            if(item.value.pins==0) iterator.remove()
        }
    }
    private suspend fun <T> withEntry(key:Key,c:PublicReadContext,block:suspend (Entry)->T):T {
        val entry=acquire(key,c)
        try {return block(entry)} finally {synchronized(entries) {entry.pins--;trim()}}
    }
    private fun normalizedQuery(raw:String):String {
        if(raw.codePointCount(0,raw.length)>300) throw Failure(AttendanceError.INVALID_INPUT)
        return raw.trim()
    }
    private fun validRevision(revision:String) {require(revision.isNotBlank() && revision.length<=64)}
    private fun current(c:PublicReadContext) {if(!auth.isCurrentPublicRead(c)) throw Failure(AttendanceError.SESSION_CHANGED)}
    private suspend fun request(path:String,c:PublicReadContext,body:String?=null):String =
        auth.requestScoped(path,c,body) ?: throw Failure(AttendanceError.SESSION_CHANGED)
    private fun invalidate(id:EventId) = synchronized(entries) {entries.filterKeys {it.event==id}.values.forEach {it.version.incrementAndGet()}}
    override fun observeDashboard(eventId:EventId)=observeDashboard(eventId,"")
    override fun observeDashboard(eventId:EventId,query:String):Flow<DashboardResult> = channelFlow<Stamped> {
        auth.observeSession().collectLatest {
            val c=auth.capturePublicRead()
            if(c.token==null || c.profile==null) {
                synchronized(entries) {entries.values.forEach {it.version.incrementAndGet();it.state.value=null};entries.clear();lastContext=c}
                send(Stamped(c,DashboardResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED)));return@collectLatest
            }
            try {
                val key=Key(eventId,normalizedQuery(query))
                withEntry(key,c) {e->
                    if(e.state.value?.context?.let {auth.isCurrentPublicRead(it)}!=true) refresh(key,e,c)
                    e.state.filterNotNull().collect {send(it)}
                }
            } catch(ex:CancellationException){throw ex} catch(ex:Exception){send(Stamped(c,DashboardResult.Failed(error(ex))))}
        }
    }.map {if(auth.isCurrentPublicRead(it.context)) it.result else DashboardResult.Failed(AttendanceError.SESSION_CHANGED)}

    override suspend fun refreshDashboard(eventId:EventId)=refreshDashboard(eventId,"")
    override suspend fun refreshDashboard(eventId:EventId,query:String):DashboardResult {
        val c=auth.capturePublicRead()
        if(c.token==null || c.profile==null) return DashboardResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED)
        return try {val key=Key(eventId,normalizedQuery(query));withEntry(key,c) {refresh(key,it,c)}}
        catch(ex:CancellationException){throw ex} catch(ex:Exception){DashboardResult.Failed(error(ex))}
    }
    private suspend fun refresh(key:Key,e:Entry,c:PublicReadContext):DashboardResult {
        val started=e.completed.get()
        return e.lock.withLock {
            current(c)
            if(e.completed.get()!=started) e.state.value?.takeIf {auth.isCurrentPublicRead(it.context)}?.let {return@withLock it.result}
            val version=e.version.get()
            val result=try {snapshot(key,c)} catch(ex:CancellationException){throw ex} catch(ex:Exception){DashboardResult.Failed(error(ex))}
            current(c)
            if(version!=e.version.get()) return@withLock DashboardResult.Failed(AttendanceError.STALE_PAGE)
            e.state.value=Stamped(c,result);e.completed.incrementAndGet()
            result
        }
    }
    private suspend fun snapshot(key:Key,c:PublicReadContext,includeVolunteers:Boolean=true,firstPageSize:Int=20):DashboardResult.Ready {
        repeat(2) {attempt->
            try {
                val (header,stats)=coroutineScope {
                    val details=async {
                        val dto=api.json.decodeFromString<EventDto>(request(apiPath("events","by-id",key.event.value),c))
                        require(dto.id==key.event.value)
                        organizer.mapEditor(dto,c) as? EventEditorResult.Published ?: throw Failure(AttendanceError.FORBIDDEN)
                    }
                    val totals=async {api.json.decodeFromString<StatsDto>(request(apiPath("events",key.event.value,"stats"),c))}
                    details.await() to totals.await()
                }
                validRevision(stats.revision)
                require(stats.registered>=0 && stats.capacity>=0 && stats.checkedIn in 0..stats.registered && stats.notCheckedIn==stats.registered-stats.checkedIn)
                val page=readPage(key,c,stats.revision,1,firstPageSize)
                val volunteerResult=if(includeVolunteers) volunteers.refreshVolunteers(key.event) else null
                val volunteerError=(volunteerResult as? VolunteersResult.Failed)?.error
                if(volunteerError in setOf(VolunteerError.FORBIDDEN,VolunteerError.NOT_FOUND,VolunteerError.AUTHENTICATION_REQUIRED,VolunteerError.SESSION_CHANGED))
                    throw Failure(when(volunteerError){VolunteerError.FORBIDDEN->AttendanceError.FORBIDDEN;VolunteerError.NOT_FOUND->AttendanceError.NOT_FOUND;VolunteerError.AUTHENTICATION_REQUIRED->AttendanceError.AUTHENTICATION_REQUIRED;else->AttendanceError.SESSION_CHANGED})
                current(c)
                val event=header.event
                val dashboard=AttendanceDashboard(AttendanceHeader(event.id,event.title,event.startsAt,event.zoneId,event.venue,
                    event.lifecycle,event.registrationOpen,header.terminal,true),
                    AttendanceSummary(stats.registered,stats.checkedIn,stats.notCheckedIn,(stats.capacity-stats.registered).coerceAtLeast(0),stats.capacity),
                    stats.revision,clock.instant(),(volunteerResult as? VolunteersResult.Ready)?.volunteers?.size,volunteerError,event.questions)
                return DashboardResult.Ready(dashboard,page)
            } catch(ex:Failure) {if(ex.error!=AttendanceError.STALE_PAGE || attempt==1) throw ex}
        }
        throw Failure(AttendanceError.STALE_PAGE)
    }
    private suspend fun readPage(key:Key,c:PublicReadContext,revision:String,page:Int,size:Int):AttendeesResult.Page {
        validRevision(revision);require(page in 1..1_000_000 && size in 1..100)
        val params="https://path.invalid/".toHttpUrl().newBuilder().addQueryParameter("search",key.query)
            .addQueryParameter("page",page.toString()).addQueryParameter("pageSize",size.toString()).addQueryParameter("revision",revision).build().encodedQuery!!
        val text=try {request(apiPath("events",key.event.value,"attendees")+"?"+params,c)}
            catch(ex:RemoteFailure){if(ex.httpStatus==409 && ex.machineCode=="STALE_PAGE") throw Failure(AttendanceError.STALE_PAGE);throw ex}
        val dto=api.json.decodeFromString<AttendeesDto>(text)
        validRevision(dto.revision)
        if(dto.revision!=revision) throw Failure(AttendanceError.STALE_PAGE)
        require(dto.page==page && dto.pageSize==size && dto.total>=0 && dto.items.size<=size)
        val offset=(page.toLong()-1)*size
        require(dto.items.size.toLong()==minOf(size.toLong(),(dto.total-offset).coerceAtLeast(0)))
        val rows=dto.items.map {it.domain()}
        require(rows.map {it.ticketId}.distinct().size==rows.size)
        current(c)
        val next=if(offset+rows.size<dto.total) AttendanceCursor(key.event,revision,key.query,serverPage=page+1,pageSize=size) else null
        return AttendeesResult.Page(revision,rows,dto.total,next,key.query,page,size)
    }
    override suspend fun attendees(eventId:EventId,query:String,cursor:AttendanceCursor?):AttendeesResult {
        val c=auth.capturePublicRead()
        if(c.token==null || c.profile==null) return AttendeesResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED)
        return try {
            val key=Key(eventId,normalizedQuery(query))
            if(cursor==null) return when(val refreshed=withEntry(key,c) {refresh(key,it,c)}) {
                is DashboardResult.Ready->refreshed.firstPage ?: AttendeesResult.Failed(AttendanceError.PROTOCOL)
                is DashboardResult.Failed->AttendeesResult.Failed(refreshed.error)
            }
            if(cursor.eventId!=eventId || cursor.query!=key.query || cursor.serverPage==null || cursor.pageSize==null) throw Failure(AttendanceError.STALE_PAGE)
            withEntry(key,c) {e->
            val version=e.version.get()
            val baseline=e.state.value?.takeIf {auth.isCurrentPublicRead(it.context)}?.result as? DashboardResult.Ready
            if(baseline?.dashboard?.revision!=cursor.revision) throw Failure(AttendanceError.STALE_PAGE)
            val page=readPage(key,c,cursor.revision,requireNotNull(cursor.serverPage),requireNotNull(cursor.pageSize))
            current(c)
            if(version!=e.version.get() || (e.state.value?.result as? DashboardResult.Ready)?.dashboard?.revision!=cursor.revision) throw Failure(AttendanceError.STALE_PAGE)
            page
            }
        } catch(ex:CancellationException){throw ex} catch(ex:Exception){AttendeesResult.Failed(error(ex))}
    }
    override suspend fun questionAnalytics(eventId:EventId):QuestionAnalyticsResult {
        val c=auth.capturePublicRead()
        if(c.token==null || c.profile==null) return QuestionAnalyticsResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED)
        return try {
            var answer:QuestionAnalytics?=null
            repeat(2) {attempt -> if(answer == null) {
                try {
                    val key=Key(eventId,"")
                    val baseline=snapshot(key,c,includeVolunteers=false,firstPageSize=100)
                    val accumulator=QuestionAnalyticsAccumulator(baseline.dashboard)
                    var page=requireNotNull(baseline.firstPage)
                    val total=page.totalMatches
                    val seen=hashSetOf<TicketId>()
                    while(true) {
                        currentCoroutineContext().ensureActive();current(c)
                        require(page.totalMatches==total)
                        require(page.rows.all {seen.add(it.ticketId)})
                        accumulator.add(page.rows)
                        val next=page.next ?: break
                        page=readPage(key,c,baseline.dashboard.revision,requireNotNull(next.serverPage),100)
                    }
                    // A questionnaire may be edited independently of the attendance revision.
                    val latest=coroutineScope {
                        val details=async {api.json.decodeFromString<EventDto>(request(apiPath("events","by-id",eventId.value),c))}
                        val stats=async {api.json.decodeFromString<StatsDto>(request(apiPath("events",eventId.value,"stats"),c))}
                        details.await() to stats.await()
                    }
                    require(latest.first.id==eventId.value)
                    val editor=organizer.mapEditor(latest.first,c) as? EventEditorResult.Published ?: throw Failure(AttendanceError.FORBIDDEN)
                    if(latest.second.revision!=baseline.dashboard.revision || editor.event.questions!=baseline.dashboard.questions) throw Failure(AttendanceError.STALE_PAGE)
                    current(c)
                    val result=accumulator.finish()
                    if(result.participants!=baseline.dashboard.summary.registered) throw Failure(AttendanceError.STALE_PAGE)
                    answer=result
                } catch(ex:Failure) {if(ex.error!=AttendanceError.STALE_PAGE || attempt==1) throw ex}
            }}
            QuestionAnalyticsResult.Ready(requireNotNull(answer))
        } catch(ex:CancellationException){throw ex} catch(ex:Exception){QuestionAnalyticsResult.Failed(error(ex))}
    }
    override suspend fun cancelRegistration(eventId:EventId,registrationId:String)=OwnerCancelResult.Failed(AttendanceError.FEATURE_UNAVAILABLE)
    override suspend fun cancelTicket(eventId:EventId,ticketId:TicketId):OwnerCancelResult {
        val c=auth.capturePublicRead()
        if(c.token==null || c.profile==null) return OwnerCancelResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED)
        val lock=synchronized(writes) {
            val candidate=writes.getOrPut(eventId){Mutex()}
            if(!candidate.tryLock()) return OwnerCancelResult.Failed(AttendanceError.INVALID_INPUT)
            candidate
        }
        invalidate(eventId)
        try {
            val dto=api.json.decodeFromString<CancellationDto>(request(apiPath("events",eventId.value,"attendees",ticketId.value,"cancel"),c,"{}"))
            require(dto.ticketId==ticketId.value && dto.status=="cancelled");current(c)
            invalidate(eventId)
            tickets.invalidateOwnerCancellation(eventId,ticketId)
            scanner.invalidateEvent(eventId)
            // The visible Dashboard VM explicitly refreshes its captured query after confirmed cancellation.
            return OwnerCancelResult.Cancelled
        } catch(ex:CancellationException){throw ex} catch(ex:Exception){
            if(!auth.isCurrentPublicRead(c)) return OwnerCancelResult.Failed(AttendanceError.SESSION_CHANGED)
            val reason=if(ex is RemoteFailure && ex.httpStatus==409 && ex.machineCode=="TICKET_USED") AttendanceError.USED else error(ex)
            if(reason in setOf(AttendanceError.FORBIDDEN,AttendanceError.NOT_FOUND,AttendanceError.AUTHENTICATION_REQUIRED)) synchronized(entries) {
                entries.filterKeys {it.event==eventId}.values.forEach {it.state.value=Stamped(c,DashboardResult.Failed(reason))}
            }
            return OwnerCancelResult.Failed(reason)
        } finally {invalidate(eventId);synchronized(writes) {lock.unlock();writes.remove(eventId,lock)}}
    }
    private fun error(ex:Exception):AttendanceError {
        if(ex is Failure) return ex.error
        if(ex !is RemoteFailure) return AttendanceError.PROTOCOL
        if(ex.httpStatus==404) return AttendanceError.NOT_FOUND
        return when(ex.error) {
            AuthError.Unauthorized->AttendanceError.AUTHENTICATION_REQUIRED;AuthError.Forbidden->AttendanceError.FORBIDDEN
            AuthError.SessionChanged->AttendanceError.SESSION_CHANGED;AuthError.Network->AttendanceError.NETWORK;AuthError.Timeout->AttendanceError.TIMEOUT
            AuthError.InvalidInput->AttendanceError.INVALID_INPUT;AuthError.ServerUnavailable->AttendanceError.SERVER_UNAVAILABLE
            is AuthError.Cooldown->AttendanceError.RATE_LIMITED;else->AttendanceError.PROTOCOL
        }
    }
}

@Serializable private data class StatsDto(val revision:String,val registered:Int,val capacity:Int,val checkedIn:Int,val notCheckedIn:Int)
@Serializable private data class AttendeesDto(val revision:String,val items:List<AttendeeDto>,val total:Int,val page:Int,val pageSize:Int)
@Serializable private data class CancellationDto(val ticketId:String,val status:String)
@Serializable private data class AttendeeDto(val ticketId:String,val ticketCode:String,val fullName:String,val email:String,
    val status:String,val checkedInAt:String?,val answers:Map<String,JsonElement>) {
    fun domain():Attendee {
        require(ticketId.isNotBlank() && ticketCode.isNotBlank())
        val ticketStatus=when(status){"present"->TicketStatus.USED;"expected"->TicketStatus.ACTIVE;"cancelled"->TicketStatus.CANCELLED;else->throw IllegalArgumentException("Unknown attendee status")}
        val history=answers.mapValues {(_,answer)->when(answer) {
            is JsonPrimitive->if(answer.isString) Answer.Text(answer.content) else Answer.Checkbox(requireNotNull(answer.booleanOrNull))
            is JsonArray->Answer.MultipleChoice(answer.map {val value=it as? JsonPrimitive;require(value!=null && value.isString);value.content})
            else->throw IllegalArgumentException("Unknown historical answer type")
        }}
        return Attendee(null,TicketId(ticketId),fullName,email,ticketCode,ticketStatus,null,checkedInAt?.let(Instant::parse),null,history)
    }
}
