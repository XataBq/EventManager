@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.eventpass.data.remote

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.eventpass.core.domain.auth.AuthError
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*

class RemoteVolunteersRepository(private val api:AuthHttp,private val auth:RemoteAuthRepository,
    private val clock:Clock=Clock.systemUTC()):VolunteerRepository {
    private data class Stamped(val context:PublicReadContext,val value:VolunteersResult)
    private class Entry {
        val read=Mutex();val write=Mutex();val state=MutableStateFlow<Stamped?>(null)
        val version=AtomicLong();var retryAt:Instant?=null
    }
    private val entries=mutableMapOf<EventId,Entry>()
    private var lastContext:PublicReadContext?=null
    init {auth.onPrivateSessionClosed {synchronized(entries) {entries.values.forEach {it.version.incrementAndGet();it.state.value=null};entries.clear();lastContext=null}}}
    private fun entry(id:EventId,c:PublicReadContext):Entry=synchronized(entries) {
        if(lastContext?.let {!auth.isCurrentPublicRead(it)}==true) {
            entries.values.forEach {it.version.incrementAndGet();it.state.value=null};entries.clear()
        }
        lastContext=c;entries.getOrPut(id){Entry()}
    }
    private fun value(e:Entry,c:PublicReadContext)=e.state.value?.takeIf {auth.isCurrentPublicRead(it.context) && it.context.snapshot==c.snapshot}?.value
    private fun publish(e:Entry,c:PublicReadContext,result:VolunteersResult) {if(auth.isCurrentPublicRead(c)) e.state.value=Stamped(c,result)}
    override fun observeVolunteers(eventId:EventId):Flow<VolunteersResult> = channelFlow<Stamped> {
        auth.observeSession().collectLatest {
            val c=auth.capturePublicRead();val e=entry(eventId,c)
            if(c.token==null || c.profile==null) {send(Stamped(c,VolunteersResult.Failed(VolunteerError.AUTHENTICATION_REQUIRED)));return@collectLatest}
            if(value(e,c)==null) read(eventId,e,c)
            e.state.filterNotNull().collect {send(it)}
        }
    }.map {if(auth.isCurrentPublicRead(it.context)) it.value else VolunteersResult.Failed(VolunteerError.SESSION_CHANGED)}

    override suspend fun refreshVolunteers(eventId:EventId):VolunteersResult {
        val c=auth.capturePublicRead();val e=entry(eventId,c)
        if(c.token==null || c.profile==null) return VolunteersResult.Failed(VolunteerError.AUTHENTICATION_REQUIRED).also {publish(e,c,it)}
        read(eventId,e,c)
        if(!auth.isCurrentPublicRead(c)) return VolunteersResult.Failed(VolunteerError.SESSION_CHANGED)
        return value(e,c) ?: VolunteersResult.Failed(VolunteerError.NETWORK)
    }
    private suspend fun read(id:EventId,e:Entry,c:PublicReadContext) {
        if(!e.read.tryLock()) return
        val version=e.version.get()
        try {
            val body=auth.requestScoped(apiPath("events",id.value,"volunteers"),c) ?: return
            val list=api.json.decodeFromString<List<VolunteerDto>>(body).map {it.domain(id)}
            require(list.map {it.grant.userId}.distinct().size==list.size)
            if(version==e.version.get()) publish(e,c,VolunteersResult.Ready(list))
        } catch(ex:CancellationException){throw ex} catch(ex:Exception) {
            if(version==e.version.get() && auth.isCurrentPublicRead(c)) {
                val error=volunteerError(ex)
                if(error in privateDenials) e.version.incrementAndGet()
                publish(e,c,VolunteersResult.Failed(error))
            }
        } finally {e.read.unlock()}
    }
    override suspend fun grant(eventId:EventId,emailOrId:String)=change(eventId,emailOrId,null)
    override suspend fun revoke(eventId:EventId,userId:UserId)=change(eventId,null,userId)
    private suspend fun change(id:EventId,identifier:String?,userId:UserId?):VolunteerOutcome {
        val c=auth.capturePublicRead();val e=entry(id,c)
        if(c.token==null || c.profile==null) return VolunteerOutcome.Failed(VolunteerError.AUTHENTICATION_REQUIRED)
        if(!e.write.tryLock()) return VolunteerOutcome.Failed(VolunteerError.INVALID_TARGET)
        val version=e.version.incrementAndGet()
        try {
            e.retryAt?.takeIf {clock.instant()<it}?.let {return VolunteerOutcome.Failed(VolunteerError.RATE_LIMITED,it)}
            val before=(value(e,c) as? VolunteersResult.Ready)?.volunteers
            val result=if(userId!=null) {
                val response=auth.requestScoped(apiPath("events",id.value,"volunteers",userId.value),c,method="DELETE")
                    ?: return VolunteerOutcome.Failed(VolunteerError.SESSION_CHANGED)
                require(response.isBlank()) // 204 carries no Volunteer JSON and no synthetic grant.
                VolunteerOutcome.Revoked(userId)
            } else {
                val target=identifier!!.trim()
                if(target.isBlank() || target.codePointCount(0,target.length)>300) return VolunteerOutcome.Failed(VolunteerError.INVALID_TARGET)
                val response=auth.requestScoped(apiPath("events",id.value,"volunteers"),c,buildJsonObject {put("identifier",target)}.toString())
                    ?: return VolunteerOutcome.Failed(VolunteerError.SESSION_CHANGED)
                VolunteerOutcome.Granted(api.json.decodeFromString<VolunteerDto>(response).domain(id))
            }
            currentCoroutineContext().ensureActive()
            if(!auth.isCurrentPublicRead(c) || version!=e.version.get()) return VolunteerOutcome.Failed(VolunteerError.SESSION_CHANGED)
            if(before!=null) when(result) {
                is VolunteerOutcome.Granted -> publish(e,c,VolunteersResult.Ready(before.filterNot {it.grant.userId==result.volunteer.grant.userId}+result.volunteer))
                is VolunteerOutcome.Revoked -> publish(e,c,VolunteersResult.Ready(before.filterNot {it.grant.userId==result.userId}))
                else -> Unit
            }
            // Invalidate GETs started before/during this mutation; only this event is refreshed.
            e.version.incrementAndGet()
            read(id,e,c)
            if(!auth.isCurrentPublicRead(c)) return VolunteerOutcome.Failed(VolunteerError.SESSION_CHANGED)
            val denial=(value(e,c) as? VolunteersResult.Failed)?.error?.takeIf {it in privateDenials}
            return if(denial!=null) VolunteerOutcome.Failed(denial) else result
        } catch(ex:CancellationException){throw ex} catch(ex:Exception) {
            if(!auth.isCurrentPublicRead(c)) return VolunteerOutcome.Failed(VolunteerError.SESSION_CHANGED)
            val error=volunteerError(ex);val cooldown=(ex as? RemoteFailure)?.error as? AuthError.Cooldown
            if(cooldown!=null) e.retryAt=cooldown.retryAt
            if(error in privateDenials) {e.version.incrementAndGet();publish(e,c,VolunteersResult.Failed(error))}
            return VolunteerOutcome.Failed(error,cooldown?.retryAt)
        } finally {e.version.incrementAndGet();e.write.unlock()}
    }
    private val privateDenials=setOf(VolunteerError.AUTHENTICATION_REQUIRED,VolunteerError.FORBIDDEN,VolunteerError.NOT_FOUND,VolunteerError.SESSION_CHANGED,VolunteerError.EVENT_FINISHED)
}

private fun volunteerError(ex:Exception):VolunteerError {
    if(ex !is RemoteFailure) return VolunteerError.PROTOCOL
    return when {
        ex.machineCode=="EVENT_FINISHED"->VolunteerError.EVENT_FINISHED
        ex.machineCode=="ACCOUNT_NOT_FOUND" || ex.machineCode=="USER_NOT_FOUND"->VolunteerError.ACCOUNT_NOT_FOUND
        ex.httpStatus==404->VolunteerError.NOT_FOUND
        else->when(ex.error) {
            AuthError.Unauthorized->VolunteerError.AUTHENTICATION_REQUIRED;AuthError.Forbidden->VolunteerError.FORBIDDEN
            AuthError.SessionChanged->VolunteerError.SESSION_CHANGED;AuthError.Network->VolunteerError.NETWORK;AuthError.Timeout->VolunteerError.TIMEOUT
            AuthError.ServerUnavailable->VolunteerError.SERVER_UNAVAILABLE;AuthError.InvalidInput->VolunteerError.INVALID_TARGET
            is AuthError.Cooldown->VolunteerError.RATE_LIMITED;else->VolunteerError.PROTOCOL
        }
    }
}
@Serializable private data class VolunteerDto(val userId:String,val publicId:String,val fullName:String,val email:String,
    val grantedAt:String,val checkedInCount:Int=0) {
    fun domain(id:EventId):Volunteer {
        require(userId.isNotBlank() && publicId.isNotBlank() && fullName.isNotBlank() && email.isNotBlank() && checkedInCount>=0)
        return Volunteer(ScanGrant(id,UserId(userId),true),email,checkedInCount,publicId,fullName,Instant.parse(grantedAt))
    }
}
