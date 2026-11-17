package ru.eventpass.data.remote

import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.AuthError
import ru.eventpass.core.domain.event.*

/** Room is authoritative for draft pending state; this is a bounded explicit foreground coordinator. */
class LocalDraftRepository(
    private val api:AuthHttp,private val auth:RemoteAuthRepository,private val online:RemoteOrganizerRepository,
    private val store:DraftStore,private val covers:RemoteCoverTransfer,private val files:CoverAssetReader,
    private val clock:Clock=Clock.systemUTC(),
):EventRepository by online {
    private data class Scope(val context:PublicReadContext,val lease:DraftLease)
    private data class Work(val account:UserId,val id:DraftId,val generation:Long)
    private data class Stamped<T>(val context:PublicReadContext,val value:T)
    private class Failure(val reason:EventEditError):Exception("Draft operation failed")
    private val scopeLock=Mutex()
    private var cachedScope:Scope?=null
    private val newIds=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val locks=java.util.concurrent.ConcurrentHashMap<Pair<UserId,DraftId>,Mutex>()
    private val active=MutableStateFlow<Set<Work>>(emptySet())
    init {auth.onPrivateSessionClosed {cachedScope=null;newIds.clear();active.value=emptySet()}}
    override fun beginDraft(id:DraftId) { newIds.add(id.value) }
    override suspend fun releaseCover(cover:EventCover) { clean(cover) }

    private suspend fun scope(captured:PublicReadContext?=null):Scope=scopeLock.withLock {
        val c=captured ?: auth.capturePublicRead()
        if(c.token==null || c.profile==null) throw Failure(EventEditError.AuthenticationRequired)
        cachedScope?.takeIf {auth.isCurrentPublicRead(it.context)}?.let {return@withLock it}
        val lease=local {store.activate(c.profile.account.id)}
        checkCurrent(c)
        Scope(c,lease).also {cachedScope=it}
    }
    private fun checkCurrent(c:PublicReadContext) {if(!auth.isCurrentPublicRead(c)) throw Failure(EventEditError.SessionChanged)}
    private suspend fun <T> local(block:suspend ()->T):T=try {block()} catch(e:CancellationException){throw e} catch(e:Exception){throw Failure(EventEditError.StorageUnavailable)}
    private suspend fun read(s:Scope,id:DraftId)=local {store.read(s.lease,id)}
    private fun wire(f:EventFields)=f.copy(cover=null,remoteCoverUrl=null,zoneId="Europe/Moscow")
    private fun same(a:EventFields?,b:EventFields?)=if(a==null || b==null) a==b else encodeFields(wire(a))==encodeFields(wire(b))
    private fun hash(body:String)=MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun path(vararg parts:String)="https://path.invalid/".toHttpUrl().newBuilder().apply {parts.forEach {addPathSegment(it)}}.build().encodedPath.removePrefix("/")
    private fun work(s:Scope,id:DraftId)=Work(s.lease.accountId,id,s.lease.generation)
    private fun terminal(row:StoredDraft)=row.serverStatus in setOf(EventLifecycle.FINISHED,EventLifecycle.CANCELLED) || row.finishedAt!=null
    private fun waitingCover(f:EventFields?)=f?.cover?.let {it.remoteAssetId==null}==true
    private fun visibleFields(row:StoredDraft):EventFields {
        val fields=row.pending ?: row.confirmed ?: EventFields()
        val uploaded=row.uploadedCover
        return if(fields.cover==null && uploaded?.remoteAssetId!=null && fields.remoteCoverAssetId==uploaded.remoteAssetId) fields.copy(cover=uploaded) else fields
    }
    private fun draft(row:StoredDraft)=EventDraft(row.id,row.accountId,null,visibleFields(row),
        publishedEventId=row.remoteId.takeIf {row.serverStatus!=null && row.serverStatus!=EventLifecycle.DRAFT},remoteEventId=row.remoteId,slug=row.slug)
    private fun presentation(s:Scope,row:StoredDraft):LocalDraftState {
        val state=if(row.state==DraftSyncState.SYNCING && work(s,row.id) !in active.value) DraftSyncState.UNCERTAIN else row.state
        return LocalDraftState(state,row.localRevision,
            row.remoteConflict?.let {DraftConflict(row.base ?: row.confirmed ?: EventFields(),row.pending ?: visibleFields(row),it)},
            row.problem,row.retryAt,waitingCover(row.pending),terminal(row))
    }
    private fun project(s:Scope,row:StoredDraft):EventEditorResult=if(row.problem==DraftProblem.FORBIDDEN) EventEditorResult.Failed(EventEditError.Forbidden)
        else EventEditorResult.Draft(draft(row),presentation(s,row))

    private suspend fun mutate(s:Scope,original:StoredDraft,change:(StoredDraft)->StoredDraft):StoredDraft {
        repeat(12) {
            checkCurrent(s.context)
            val current=read(s,original.id) ?: throw Failure(EventEditError.NotFound)
            if(current.incarnation!=original.incarnation) throw Failure(EventEditError.SessionChanged)
            val next=change(current).copy(storageVersion=current.storageVersion+1)
            checkCurrent(s.context)
            if(local {store.replace(s.lease,current,next)}) {checkCurrent(s.context);return next}
        }
        throw Failure(EventEditError.Conflict)
    }
    private suspend fun exact(s:Scope,original:StoredDraft,change:(StoredDraft)->StoredDraft)=mutate(s,original) {current->
        if(current.storageVersion!=original.storageVersion) throw Failure(EventEditError.Conflict)
        change(current)
    }
    private fun editError(e:Exception):EventEditError=when(e) {
        is Failure -> e.reason
        is RemoteFailure -> when(e.error) {
            AuthError.Network -> EventEditError.Network
            AuthError.Timeout -> EventEditError.Timeout
            AuthError.ServerUnavailable -> EventEditError.ServerUnavailable
            AuthError.StorageUnavailable -> EventEditError.StorageUnavailable
            AuthError.Unauthorized -> EventEditError.AuthenticationRequired
            AuthError.SessionChanged -> EventEditError.SessionChanged
            AuthError.Forbidden -> EventEditError.Forbidden
            is AuthError.Cooldown -> EventEditError.RateLimited(e.error.retryAt)
            else -> when {
                e.httpStatus==404 -> EventEditError.NotFound
                e.machineCode=="EVENT_FINISHED" -> EventEditError.EventFinished
                e.fields.isNotEmpty() -> EventEditError.Validation(e.fields.keys.map {if(it.substringBefore('.')=="location") "venue" else it.substringBefore('.')}.toSet())
                e.httpStatus==422 -> EventEditError.Validation(setOf("event"))
                else -> EventEditError.Protocol
            }
        }
        else -> EventEditError.Protocol
    }
    private fun problem(error:EventEditError)=when(error) {
        EventEditError.Network -> DraftProblem.NETWORK
        EventEditError.Timeout -> DraftProblem.TIMEOUT
        EventEditError.ServerUnavailable -> DraftProblem.NETWORK
        EventEditError.StorageUnavailable -> DraftProblem.STORAGE
        EventEditError.Forbidden -> DraftProblem.FORBIDDEN
        EventEditError.AuthenticationRequired,EventEditError.SessionChanged -> DraftProblem.AUTHENTICATION
        is EventEditError.Validation -> DraftProblem.VALIDATION
        is EventEditError.RateLimited -> DraftProblem.RATE_LIMITED
        else -> DraftProblem.PROTOCOL
    }
    private suspend fun <T> result(block:suspend ()->T):EventEditResult<T> = try {EventEditResult.Saved(block())}
        catch(e:CancellationException){throw e} catch(e:Exception){EventEditResult.Failed(editError(e))}
    private suspend fun request(s:Scope,p:String,body:String?=null,key:String?=null,method:String?=null):EventDto {
        checkCurrent(s.context)
        val text=auth.requestScoped(p,s.context,body,key,method) ?: throw Failure(EventEditError.SessionChanged)
        currentCoroutineContext().ensureActive();checkCurrent(s.context)
        return api.json.decodeFromString<EventDto>(text).also {online.mapEditor(it,s.context)}
    }
    private suspend fun remote(s:Scope,id:EventId)=request(s,path("events","by-id",id.value)).also {require(it.id==id.value)}
    private fun fields(s:Scope,dto:EventDto):EventFields=when(val mapped=online.mapEditor(dto,s.context)) {
        is EventEditorResult.Draft -> mapped.draft.fields
        is EventEditorResult.Published -> mapped.event.toFields()
        else -> error("Owner event missing")
    }
    private fun status(dto:EventDto)=EventLifecycle.valueOf(dto.status.uppercase(java.util.Locale.ROOT))
    private suspend fun clean(cover:EventCover?) {
        // A cleanup failure must not turn an already committed server acknowledgement into a failed save.
        // The startup/import orphan sweep retries files that have no durable references.
        if(cover!=null) withContext(NonCancellable) {runCatching {if(cover.assetId !in local {store.retainedCovers()}) files.release(cover)}}
    }

    override suspend fun saveDraft(id:DraftId,expectedRevision:Long?,fields:EventFields):EventEditResult<EventDraft> = result {
        val s=scope()
        val invalid=EventEditingPolicy.storageErrors(fields)
        if(invalid.isNotEmpty()) throw Failure(EventEditError.Validation(invalid))
        repeat(12) {
            val previous=read(s,id)
            if(previous==null && id.value !in newIds) throw Failure(EventEditError.NotFound)
            val priorUpload=previous?.uploadedCover
            val input=if(fields.cover?.assetId==priorUpload?.assetId && priorUpload?.remoteAssetId!=null)
                fields.copy(cover=priorUpload,remoteCoverAssetId=priorUpload.remoteAssetId) else fields
            val next=if(previous==null) {
                val submitted=wire(input)
                val body=JsonObject(encodeFields(submitted)+("publish" to kotlinx.serialization.json.JsonPrimitive(false))).toString()
                StoredDraft(s.lease.accountId,id,UUID.randomUUID().toString(),pending=input,
                    command=StoredDraftCommand(DraftCommandKind.CREATE,UUID.randomUUID().toString(),body,hash(body),submitted,1))
            } else previous.copy(storageVersion=previous.storageVersion+1,localRevision=previous.localRevision+1,pending=input,
                command=if(previous.remoteId==null && previous.command==null) {
                    val submitted=wire(input)
                    val body=JsonObject(encodeFields(submitted)+("publish" to kotlinx.serialization.json.JsonPrimitive(false))).toString()
                    StoredDraftCommand(DraftCommandKind.CREATE,UUID.randomUUID().toString(),body,hash(body),submitted,previous.localRevision+1)
                } else previous.command,
                state=if(previous.state==DraftSyncState.CONFLICT) DraftSyncState.CONFLICT else if(previous.command?.firstAttemptAt!=null) previous.state else DraftSyncState.LOCAL,
                problem=null,invalidFields=emptySet())
            checkCurrent(s.context)
            if(local {store.replace(s.lease,previous,next)}) {
                checkCurrent(s.context)
                clean(previous?.pending?.cover?.takeIf {it.assetId!=input.cover?.assetId})
                return@result draft(next)
            }
        }
        throw Failure(EventEditError.Conflict)
    }

    private suspend fun acknowledge(s:Scope,original:StoredDraft,command:StoredDraftCommand,dto:EventDto):StoredDraft {
        val confirmed=fields(s,dto)
        val next=mutate(s,original) {current->
            if(current.command?.id!=command.id) throw Failure(EventEditError.Conflict)
            val newer=current.localRevision>command.revision || waitingCover(current.pending)
            current.copy(remoteId=EventId(dto.id),slug=EventSlug(dto.slug),confirmed=confirmed,base=confirmed,
                pending=if(newer) current.pending else null,command=null,remoteConflict=null,
                state=if(newer) DraftSyncState.LOCAL else if(status(dto)==EventLifecycle.DRAFT) DraftSyncState.SYNCED else DraftSyncState.PUBLISHED,
                serverStatus=status(dto),finishedAt=dto.finishedAt?.let(Instant::parse),problem=null,retryAt=null,invalidFields=emptySet())
        }
        clean(original.pending?.cover)
        return next
    }
    private suspend fun conflict(s:Scope,row:StoredDraft,dto:EventDto)=exact(s,row) {current->current.copy(
        confirmed=fields(s,dto),remoteConflict=fields(s,dto),state=DraftSyncState.CONFLICT,
        serverStatus=status(dto),finishedAt=dto.finishedAt?.let(Instant::parse),problem=null)}

    override suspend fun syncDraft(id:DraftId):EventEditResult<EventDraft> = result {
        val s=scope();val lock=locks.getOrPut(s.lease.accountId to id){Mutex()}
        if(!lock.tryLock()) return@result draft(read(s,id) ?: throw Failure(EventEditError.NotFound))
        val key=work(s,id);active.update {it+key}
        var current:StoredDraft?=null
        var uploading=false
        try {
            // Bounded current-draft foreground work: create, optional upload, patch, one newer saved edit.
            repeat(4) {
                val row=read(s,id) ?: throw Failure(EventEditError.NotFound);current=row
                val remoteId=row.remoteId
                checkCurrent(s.context)
                if(row.state==DraftSyncState.CONFLICT || row.retryAt?.let {clock.instant()<it}==true) return@result draft(row)
                if(remoteId==null) {
                    val command=row.command?.takeIf {it.kind==DraftCommandKind.CREATE} ?: throw Failure(EventEditError.Conflict)
                    if(command.firstAttemptAt?.let {clock.instant()>=it.plusSeconds(86400)}==true) {
                        val blocked=mutate(s,row){c->c.copy(state=DraftSyncState.UNCERTAIN,problem=DraftProblem.EXPIRED_CREATE)}
                        return@result draft(blocked)
                    }
                    val sending=mutate(s,row){c->c.copy(state=DraftSyncState.SYNCING,command=command.copy(firstAttemptAt=command.firstAttemptAt ?: clock.instant()))}
                    current=sending
                    val sent=requireNotNull(sending.command)
                    val dto=request(s,"events",sent.body,sent.id)
                    acknowledge(s,sending,sent,dto)
                    return@repeat
                }
                val unknown=row.command?.takeIf {it.kind==DraftCommandKind.PATCH && it.firstAttemptAt!=null}
                if(unknown!=null) {
                    val dto=remote(s,remoteId)
                    if(same(fields(s,dto),unknown.fields)) acknowledge(s,row,unknown,dto)
                    else return@result draft(conflict(s,row,dto))
                    return@repeat
                }
                val pending=row.pending ?: return@result draft(row)
                if(terminal(row)) throw Failure(EventEditError.EventFinished)
                if(waitingCover(pending)) {
                    val cover=pending.cover!!
                    current=mutate(s,row){c->c.copy(state=DraftSyncState.SYNCING)}
                    uploading=true
                    val uploaded=covers.uploadPending(cover,s.context)
                    uploading=false
                    mutate(s,row){c->val fields=c.pending
                        if(fields?.cover?.assetId==cover.assetId) c.copy(
                        uploadedCover=uploaded,pending=fields.copy(cover=uploaded,remoteCoverAssetId=uploaded.remoteAssetId),state=DraftSyncState.LOCAL,problem=null)
                        else c.copy(state=DraftSyncState.LOCAL)}
                    clean(cover)
                    return@repeat
                }
                val dto=remote(s,remoteId)
                if(read(s,id)?.storageVersion!=row.storageVersion) return@repeat
                val actual=fields(s,dto)
                if(!same(row.base ?: row.confirmed,actual) || row.serverStatus!=status(dto)) {
                    if(same(pending,actual)) {
                        exact(s,row){c->c.copy(confirmed=actual,base=actual,pending=null,state=if(status(dto)==EventLifecycle.DRAFT) DraftSyncState.SYNCED else DraftSyncState.PUBLISHED,
                            serverStatus=status(dto),finishedAt=dto.finishedAt?.let(Instant::parse),problem=null)}
                        clean(pending.cover);return@repeat
                    }
                    return@result draft(conflict(s,row,dto))
                }
                if(status(dto) in setOf(EventLifecycle.FINISHED,EventLifecycle.CANCELLED) || dto.finishedAt!=null) return@result draft(conflict(s,row,dto))
                val desired=wire(pending)
                val patch=JsonObject(encodeFields(desired).filter {(name,value)->encodeFields(actual)[name]!=value}).toString()
                if(patch=="{}") {
                    exact(s,row){c->c.copy(confirmed=actual,base=actual,pending=null,state=DraftSyncState.SYNCED,problem=null)}
                    clean(pending.cover);return@repeat
                }
                val command=StoredDraftCommand(DraftCommandKind.PATCH,UUID.randomUUID().toString(),patch,hash(patch),desired,row.localRevision,clock.instant())
                val sending=exact(s,row){c->c.copy(state=DraftSyncState.SYNCING,command=command,confirmed=actual,base=actual,problem=null)}
                current=sending
                val response=request(s,path("events",remoteId.value),command.body,method="PATCH")
                acknowledge(s,sending,command,response)
            }
            draft(read(s,id) ?: throw Failure(EventEditError.NotFound))
        } catch(e:Exception) {
            withContext(NonCancellable) {
                val row=current
                if(row!=null && auth.isCurrentPublicRead(s.context)) runCatching {mutate(s,row){c->
                    val error=editError(e)
                    val rejectedCreate=c.command?.kind==DraftCommandKind.CREATE && error is EventEditError.Validation
                    if(c.command==null && c.pending==null) c else c.copy(command=if(rejectedCreate) null else c.command,
                        state=if(!rejectedCreate && (c.command?.firstAttemptAt!=null || uploading)) DraftSyncState.UNCERTAIN else DraftSyncState.LOCAL,
                        problem=if(uploading) DraftProblem.UPLOAD_UNKNOWN else if(e is CancellationException && c.command?.firstAttemptAt==null) null else problem(error),
                        retryAt=(error as? EventEditError.RateLimited)?.retryAt,invalidFields=(error as? EventEditError.Validation)?.fields ?: emptySet())
                }}
            }
            throw e
        } finally {active.update {it-key};lock.unlock()}
    }

    override suspend fun resolveDraft(id:DraftId,resolution:DraftResolution,merged:EventFields?):EventEditResult<EventDraft> = result {
        val s=scope();val row=read(s,id) ?: throw Failure(EventEditError.NotFound)
        val remote=row.remoteConflict ?: throw Failure(EventEditError.Conflict)
        if(resolution!=DraftResolution.REMOTE && terminal(row)) throw Failure(EventEditError.EventFinished)
        val chosen=when(resolution){DraftResolution.REMOTE->remote;DraftResolution.LOCAL->merged ?: row.pending ?: visibleFields(row);DraftResolution.MERGED->merged ?: throw Failure(EventEditError.Conflict)}
        val invalid=EventEditingPolicy.storageErrors(chosen);if(invalid.isNotEmpty()) throw Failure(EventEditError.Validation(invalid))
        val next=mutate(s,row){c->
            if(c.state!=DraftSyncState.CONFLICT || c.remoteConflict!=remote) throw Failure(EventEditError.Conflict)
            c.copy(localRevision=c.localRevision+1,confirmed=remote,base=remote,pending=chosen.takeUnless {resolution==DraftResolution.REMOTE},command=null,
                state=if(resolution!=DraftResolution.REMOTE) DraftSyncState.LOCAL else if(c.serverStatus==EventLifecycle.DRAFT) DraftSyncState.SYNCED else DraftSyncState.PUBLISHED,
                remoteConflict=null,problem=null,retryAt=null,invalidFields=emptySet())
        }
        clean(row.pending?.cover);draft(next)
    }
    override suspend fun discardDraft(id:DraftId):EventEditResult<Unit> = result {
        val s=scope();val row=read(s,id) ?: return@result Unit
        if(row.remoteId==null) {
            checkCurrent(s.context);if(!local {store.delete(s.lease,row)}) throw Failure(EventEditError.Conflict)
        } else mutate(s,row){c->c.copy(localRevision=c.localRevision+1,pending=null,base=c.confirmed,command=null,remoteConflict=null,problem=null,
            state=if(c.serverStatus==EventLifecycle.DRAFT) DraftSyncState.SYNCED else DraftSyncState.PUBLISHED)}
        clean(row.pending?.cover)
    }

    private suspend fun importRemote(s:Scope,dto:EventDto):StoredDraft {
        local {store.findRemote(s.lease,EventId(dto.id))}?.let {return it}
        val confirmed=fields(s,dto)
        val row=StoredDraft(s.lease.accountId,DraftId(UUID.randomUUID().toString()),UUID.randomUUID().toString(),remoteId=EventId(dto.id),slug=EventSlug(dto.slug),
            confirmed=confirmed,base=confirmed,pending=null,state=if(status(dto)==EventLifecycle.DRAFT) DraftSyncState.SYNCED else DraftSyncState.PUBLISHED,
            serverStatus=status(dto),finishedAt=dto.finishedAt?.let(Instant::parse))
        checkCurrent(s.context);if(!local {store.replace(s.lease,null,row)}) throw Failure(EventEditError.Conflict)
        return row
    }
    private suspend fun refresh(s:Scope,row:StoredDraft) {
        val id=row.remoteId ?: return
        try {
            val dto=remote(s,id);val actual=fields(s,dto)
            val current=read(s,row.id) ?: return
            if(current.incarnation!=row.incarnation || current.storageVersion!=row.storageVersion || work(s,row.id) in active.value) return
            val command=current.command
            if(command?.kind==DraftCommandKind.PATCH && command.firstAttemptAt!=null && same(actual,command.fields)) {
                acknowledge(s,current,command,dto);return
            }
            if(current.pending!=null && (!same(current.base ?: current.confirmed,actual) || current.serverStatus!=status(dto))) {conflict(s,current,dto);return}
            val next=current.copy(storageVersion=current.storageVersion+1,confirmed=actual,base=if(current.pending==null) actual else current.base,
                serverStatus=status(dto),finishedAt=dto.finishedAt?.let(Instant::parse),slug=EventSlug(dto.slug),problem=null,
                state=if(current.pending!=null || current.command!=null) current.state else if(status(dto)==EventLifecycle.DRAFT) DraftSyncState.SYNCED else DraftSyncState.PUBLISHED)
            checkCurrent(s.context);local {store.replace(s.lease,current,next)}
        } catch(e:CancellationException){throw e} catch(e:Exception) {
            if(auth.isCurrentPublicRead(s.context)) runCatching {mutate(s,row){c->c.copy(problem=problem(editError(e)))}}
        }
    }

    override fun observeEditor(target:EventEditorTarget):Flow<EventEditorResult> {
        if(target is EventEditorTarget.Published) return online.observeEditor(target)
        return channelFlow<Stamped<EventEditorResult>> {
            auth.observeSession().collectLatest {
                val captured=auth.capturePublicRead()
                try {
                    val s=scope(captured)
                    val row=when(target){is EventEditorTarget.Draft->read(s,target.id);is EventEditorTarget.RemoteDraft->local {store.findRemote(s.lease,target.id)};else->null}
                        ?: if(target is EventEditorTarget.RemoteDraft) importRemote(s,remote(s,target.id)) else null
                    val id=row?.id ?: (target as? EventEditorTarget.Draft)?.id
                    if(id==null || row==null && id.value !in newIds) {send(Stamped(captured,EventEditorResult.Failed(EventEditError.NotFound)));return@collectLatest}
                    if(row?.serverStatus!=null && row.serverStatus!=EventLifecycle.DRAFT && row.pending==null && row.command==null) {
                        online.observeEditor(EventEditorTarget.Published(row.remoteId!!)).collect {send(Stamped(captured,it))};return@collectLatest
                    }
                    coroutineScope {
                        launch {
                            combine(store.observe(s.lease),active){rows,_->rows.firstOrNull {it.id==id}}.collect {current->
                                val value=if(current!=null) project(s,current) else if(row==null) EventEditorResult.Draft(EventDraft(id,s.lease.accountId,null,EventFields()),LocalDraftState(DraftSyncState.UNSAVED)) else EventEditorResult.Failed(EventEditError.NotFound)
                                send(Stamped(s.context,value))
                            }
                        }
                        if(row?.remoteId!=null) launch {refresh(s,row)}
                        awaitCancellation()
                    }
                } catch(e:CancellationException){throw e} catch(e:Exception){send(Stamped(captured,EventEditorResult.Failed(editError(e))))}
            }
        }.map {if(auth.isCurrentPublicRead(it.context)) it.value else EventEditorResult.Failed(EventEditError.SessionChanged)}
    }
    override suspend fun refreshEditor(target:EventEditorTarget) {
        if(target is EventEditorTarget.Published) {online.refreshEditor(target);return}
        val s=scope();val row=when(target){is EventEditorTarget.Draft->read(s,target.id);is EventEditorTarget.RemoteDraft->local {store.findRemote(s.lease,target.id)};else->null}
        if(row!=null) refresh(s,row)
    }
    private fun summaries(s:Scope,rows:List<StoredDraft>,cached:Boolean):List<LocalDraftSummary> {
        val selected=rows.filter {it.state!=DraftSyncState.PUBLISHED && it.problem!=DraftProblem.FORBIDDEN && (cached || it.pending!=null || it.command!=null)}
        val pending=selected.filter {it.pending!=null || it.command!=null}
        val confirmed=selected.filter {it.pending==null && it.command==null && pending.none {p->p.remoteId!=null && p.remoteId==it.remoteId}}
            .distinctBy {it.remoteId?.value ?: it.id.value}
        return (pending+confirmed).map {LocalDraftSummary(it.id,it.remoteId,visibleFields(it).title,presentation(s,it).state)}
    }
    override fun observeMyEvents()=observeMyEvents(MyEventsTab.ORGANIZING)
    override fun observeMyEvents(tab:MyEventsTab):Flow<MyEventsResult> {
        if(tab!=MyEventsTab.DRAFTS) return online.observeMyEvents(tab)
        return channelFlow<Stamped<MyEventsResult>> {
            auth.observeSession().collectLatest {
                val c=auth.capturePublicRead()
                try {val s=scope(c)
                    combine(online.observeMyEvents(tab),store.observe(s.lease),active){remote,rows,_->
                        when(remote){is MyEventsResult.Ready->remote.copy(localDrafts=summaries(s,rows,false));is MyEventsResult.Failed->remote.copy(localDrafts=summaries(s,rows,true))}
                    }.collect {send(Stamped(c,it))}
                } catch(e:CancellationException){throw e} catch(e:Exception){send(Stamped(c,MyEventsResult.Failed(editError(e))))}
            }
        }.map {if(auth.isCurrentPublicRead(it.context)) it.value else MyEventsResult.Failed(EventEditError.SessionChanged)}
    }
    override suspend fun publishDraft(id:DraftId,expectedRevision:Long?):EventEditResult<EventId> = result {
        when(val synced=syncDraft(id)){is EventEditResult.Failed->throw Failure(synced.error);else->Unit}
        val s=scope();val row=read(s,id) ?: throw Failure(EventEditError.NotFound)
        val remoteId=row.remoteId ?: throw Failure(EventEditError.Conflict)
        if(row.pending!=null || row.command!=null || row.state==DraftSyncState.CONFLICT) throw Failure(EventEditError.Conflict)
        val current=remote(s,remoteId)
        val response=if(status(current)!=EventLifecycle.DRAFT) current else {
            if(!same(row.confirmed,fields(s,current))) {conflict(s,row,current);throw Failure(EventEditError.Conflict)}
            request(s,path("events",remoteId.value,"publish"),"{}")
        }
        if(status(response) in setOf(EventLifecycle.FINISHED,EventLifecycle.CANCELLED)) throw Failure(EventEditError.EventFinished)
        mutate(s,row){c->c.copy(confirmed=fields(s,response),base=fields(s,response),serverStatus=status(response),slug=EventSlug(response.slug),
            state=if(c.pending==null) DraftSyncState.PUBLISHED else DraftSyncState.LOCAL)}
        remoteId
    }
    override suspend fun updateEvent(id:EventId,expectedRevision:Long?,expectedLocalSchemaRevision:Int?,fields:EventFields):EventEditResult<Event> = result {
        val s=scope()
        val cover=fields.cover
        val uploaded=if(cover!=null && cover.remoteAssetId==null) covers.uploadPending(cover,s.context) else cover
        val ready=fields.copy(cover=uploaded,remoteCoverAssetId=uploaded?.remoteAssetId ?: fields.remoteCoverAssetId)
        when(val result=online.updateEvent(id,expectedRevision,expectedLocalSchemaRevision,ready)) {
            is EventEditResult.Failed -> throw Failure(result.error)
            is EventEditResult.Saved -> {clean(cover);result.value}
        }
    }
}
