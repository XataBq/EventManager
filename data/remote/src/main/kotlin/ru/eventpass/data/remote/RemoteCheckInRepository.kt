@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.eventpass.data.remote

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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

/** Foreground server authority. Only fingerprints/retention metadata live here, never queued credentials. */
class RemoteCheckInRepository(private val api:AuthHttp,private val auth:RemoteAuthRepository,
    private val clock:Clock=Clock.systemUTC()):CheckInRepository {
    private data class Stamped(val context:PublicReadContext,val value:ScannerAccess)
    private data class Attempt(val context:PublicReadContext,val digest:List<Byte>,val firstAt:Instant)
    private class Entry {
        val read=Mutex();val write=Mutex();val state=MutableStateFlow<Stamped?>(null)
        val version=AtomicLong();val attempts=ConcurrentHashMap<String,Attempt>()
        var retryAt:Instant?=null
    }
    private val entries=mutableMapOf<EventId,Entry>()
    private var lastContext:PublicReadContext?=null
    private val scopes=AtomicLong()
    init {auth.onPrivateSessionClosed {synchronized(entries) {entries.values.forEach {it.version.incrementAndGet();it.state.value=null;it.attempts.clear()};entries.clear();lastContext=null}}}
    private fun entry(id:EventId,c:PublicReadContext):Entry=synchronized(entries) {
        if(lastContext?.let { !auth.isCurrentPublicRead(it) }==true) {
            entries.values.forEach {it.version.incrementAndGet();it.state.value=null;it.attempts.clear()};entries.clear()
        }
        lastContext=c
        entries.getOrPut(id){Entry()}
    }
    override fun parseManual(raw:String)=ServerTicketCodePolicy.parse(raw)
    /** No hidden polling: the existing visible/resume reader reloads this event after owner cancellation. */
    internal fun invalidateEvent(id:EventId) = synchronized(entries) {
        entries[id]?.let {it.version.incrementAndGet();it.state.value=null}
        Unit
    }
    private fun current(c:PublicReadContext)=auth.isCurrentPublicRead(c)
    private fun access(e:Entry,c:PublicReadContext)=e.state.value?.takeIf {current(it.context) && it.context.snapshot==c.snapshot}?.value
    private fun denial(e:Entry,c:PublicReadContext,error:CheckInError) {
        if(current(c)) {e.version.incrementAndGet();e.state.value=Stamped(c,ScannerAccess.Denied(error))}
    }
    override fun observeAccess(eventId:EventId):Flow<ScannerAccess> = channelFlow<Stamped> {
        auth.observeSession().collectLatest {
            val c=auth.capturePublicRead();val e=entry(eventId,c)
            if(c.token==null || c.profile==null) {send(Stamped(c,ScannerAccess.Denied(CheckInError.AUTHENTICATION_REQUIRED)));return@collectLatest}
            if(access(e,c)==null) read(eventId,e,c)
            e.state.filterNotNull().collect {send(it)}
        }
    }.map {if(current(it.context)) it.value else ScannerAccess.Denied(CheckInError.SESSION_CHANGED)}

    override suspend fun refreshAccess(eventId:EventId) {
        val c=auth.capturePublicRead();val e=entry(eventId,c)
        if(c.token==null || c.profile==null) {denial(e,c,CheckInError.AUTHENTICATION_REQUIRED);return}
        read(eventId,e,c)
    }
    private suspend fun read(id:EventId,e:Entry,c:PublicReadContext) {
        if(!e.read.tryLock()) return
        val version=e.version.get()
        try {
            val text=auth.requestScoped(apiPath("events",id.value,"scanner"),c) ?: return
            val dto=api.json.decodeFromString<ScannerInfoDto>(text)
            val summary=dto.event.domain()
            require(summary.id==id && summary.registeredCount>=0 && summary.checkedInCount in 0..summary.registeredCount && dto.checkedByMe>=0)
            if(summary.status in setOf(EventLifecycle.FINISHED,EventLifecycle.CANCELLED) || summary.finishedAt!=null) {
                if(version==e.version.get()) denial(e,c,CheckInError.EVENT_FINISHED)
                return
            }
            require(summary.status in setOf(EventLifecycle.PUBLISHED,EventLifecycle.LIVE))
            val role=when(dto.viewerRole){"organizer"->ScannerRole.ORGANIZER;"volunteer"->ScannerRole.VOLUNTEER;else->throw IllegalArgumentException("Unknown scanner role")}
            val recent=dto.recent.map {log->
                require(log.id.isNotBlank())
                ScanLogEntry(log.id,Instant.parse(log.scannedAt),decision(log.result),log.holderName,log.ticketCode,log.note)
            }
            require(recent.map {it.id}.distinct().size==recent.size)
            if(!current(c) || version!=e.version.get()) return
            val old=access(e,c) as? ScannerAccess.Allowed
            val scope=old?.takeIf {it.info?.role==role}?.scope ?: ScannerScope(c.profile!!.account.id,scopes.incrementAndGet())
            e.state.value=Stamped(c,ScannerAccess.Allowed(id,summary.title,scope,ScannerInfo(summary,role,dto.checkedByMe,recent)))
        } catch(ex:CancellationException){throw ex} catch(ex:Exception) {
            if(current(c) && version==e.version.get()) {
                val error=scanError(ex);val old=access(e,c) as? ScannerAccess.Allowed
                if(old!=null && error in setOf(CheckInError.NETWORK,CheckInError.TIMEOUT,CheckInError.SERVER_UNAVAILABLE,CheckInError.RATE_LIMITED))
                    e.state.value=Stamped(c,old.copy(readError=error))
                else denial(e,c,error)
            }
        } finally {e.read.unlock()}
    }
    override suspend fun checkIn(eventId:EventId,credential:TicketCredential)=checkIn(eventId,ScanCommand(credential))
    override suspend fun checkIn(eventId:EventId,command:ScanCommand):CheckInOutcome {
        val c=auth.capturePublicRead();val e=entry(eventId,c)
        if(c.token==null || c.profile==null) return CheckInOutcome.Failed(CheckInError.AUTHENTICATION_REQUIRED)
        val allowed=access(e,c) as? ScannerAccess.Allowed ?: return CheckInOutcome.Failed((access(e,c) as? ScannerAccess.Denied)?.error ?: CheckInError.FORBIDDEN)
        if(command.scope!=null && command.scope!=allowed.scope) return CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
        if(!e.write.tryLock()) return CheckInOutcome.Failed(CheckInError.PENDING_RETRY,uncertain=true)
        try {
            if(!current(c)) return CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
            val code=when(val credential=command.credential){is TicketCredential.Qr->credential.token.value;is TicketCredential.Manual->credential.code.value}
            val body=buildJsonObject {put("code",code)}.toString()
            val digest=MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).toList()
            var attempt=e.attempts[command.id]
            if(attempt!=null && (!current(attempt.context) || attempt.digest!=digest)) return CheckInOutcome.Failed(CheckInError.RECONCILIATION_REQUIRED,uncertain=true)
            if(attempt==null) {
                if(code.isBlank() || code.codePointCount(0,code.length)>300 ||
                    command.credential is TicketCredential.Manual && ServerTicketCodePolicy.parse(code)?.value!=code)
                    return CheckInOutcome.Failed(CheckInError.INVALID_INPUT)
                if(!command.id.matches(Regex("[A-Za-z0-9._:-]{16,128}"))) return CheckInOutcome.Failed(CheckInError.INVALID_INPUT)
                // Never evict an unresolved command and silently grant it a fresh retention window.
                if(e.attempts.size>=32) return CheckInOutcome.Failed(CheckInError.RECONCILIATION_REQUIRED,uncertain=true)
            }
            if((attempt?.firstAt ?: command.firstAttemptAt)?.let {clock.instant()>=it.plusSeconds(86400)}==true)
                return CheckInOutcome.Failed(CheckInError.RECONCILIATION_REQUIRED,uncertain=true)
            e.retryAt?.takeIf {clock.instant()<it}?.let {return CheckInOutcome.Failed(CheckInError.RATE_LIMITED,it,true)}
            if(attempt==null) {attempt=Attempt(c,digest,command.markAttempt(clock.instant()));e.attempts[command.id]=attempt}
            val version=e.version.get()
            val text=auth.requestScoped(apiPath("events",eventId.value,"scan"),c,body,command.id)
                ?: return CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
            val dto=api.json.decodeFromString<ScanResultDto>(text)
            val result=CheckInOutcome.Confirmed(decision(dto.result),ScanReceipt(dto.scanId,dto.reason,dto.ticketCode,dto.holderName,
                dto.category,dto.checkedInAt?.let(Instant::parse),dto.checkedInBy),dto.replayed==true)
            currentCoroutineContext().ensureActive()
            if(!current(c) || version!=e.version.get() || (access(e,c) as? ScannerAccess.Allowed)?.scope!=allowed.scope)
                return CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
            e.attempts.remove(command.id);e.retryAt=null
            // Counts/history come from the server read; no local increment, including replayed receipts.
            read(eventId,e,c)
            if(!current(c)) return CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
            if((access(e,c) as? ScannerAccess.Allowed)?.scope!=allowed.scope)
                return CheckInOutcome.Failed((access(e,c) as? ScannerAccess.Denied)?.error ?: CheckInError.SESSION_CHANGED)
            return result
        } catch(ex:CancellationException){throw ex} catch(ex:Exception) {
            if(!current(c)) return CheckInOutcome.Failed(CheckInError.SESSION_CHANGED)
            val error=scanError(ex)
            val retry=(ex as? RemoteFailure)?.error as? AuthError.Cooldown
            if(retry!=null) e.retryAt=retry.retryAt
            if(error in setOf(CheckInError.FORBIDDEN,CheckInError.NOT_FOUND,CheckInError.EVENT_FINISHED,CheckInError.AUTHENTICATION_REQUIRED)) denial(e,c,error)
            val unknown=error in setOf(CheckInError.NETWORK,CheckInError.TIMEOUT,CheckInError.SERVER_UNAVAILABLE,CheckInError.PROTOCOL,
                CheckInError.RATE_LIMITED,CheckInError.RECONCILIATION_REQUIRED)
            if(!unknown) e.attempts.remove(command.id)
            return CheckInOutcome.Failed(error,retry?.retryAt,unknown)
        } finally {e.write.unlock()}
    }
}

private fun decision(value:String)=when(value){"allowed"->ScanDecision.ALLOWED;"already_used"->ScanDecision.ALREADY_USED;"invalid"->ScanDecision.INVALID;else->throw IllegalArgumentException("Unknown scan result")}
private fun scanError(ex:Exception):CheckInError {
    if(ex !is RemoteFailure) return CheckInError.PROTOCOL
    return when {
        ex.machineCode=="EVENT_FINISHED" -> CheckInError.EVENT_FINISHED
        ex.machineCode=="IDEMPOTENCY_CONFLICT" -> CheckInError.RECONCILIATION_REQUIRED
        ex.httpStatus==404 -> CheckInError.NOT_FOUND
        else -> when(ex.error) {
            AuthError.Unauthorized->CheckInError.AUTHENTICATION_REQUIRED;AuthError.Forbidden->CheckInError.FORBIDDEN
            AuthError.SessionChanged->CheckInError.SESSION_CHANGED;AuthError.Network->CheckInError.NETWORK;AuthError.Timeout->CheckInError.TIMEOUT
            AuthError.ServerUnavailable->CheckInError.SERVER_UNAVAILABLE;AuthError.InvalidInput->CheckInError.INVALID_INPUT
            is AuthError.Cooldown->CheckInError.RATE_LIMITED;else->CheckInError.PROTOCOL
        }
    }
}

@Serializable private data class ScannerInfoDto(val event:EventSummaryDto,val viewerRole:String,val checkedByMe:Int,val recent:List<ScanLogDto>)
@Serializable private data class ScanLogDto(val id:String,val scannedAt:String,val result:String,val holderName:String?,val ticketCode:String?,val note:String)
@Serializable private data class ScanResultDto(val result:String,val scanId:String?=null,val replayed:Boolean?=null,val reason:String?=null,
    val ticketCode:String?=null,val holderName:String?=null,val category:String?=null,val checkedInAt:String?=null,val checkedInBy:String?=null)
