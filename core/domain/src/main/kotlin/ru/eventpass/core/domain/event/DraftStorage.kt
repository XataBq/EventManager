package ru.eventpass.core.domain.event

import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class DraftSyncState { UNSAVED, LOCAL, SYNCING, SYNCED, UNCERTAIN, CONFLICT, PUBLISHED }
enum class DraftProblem { NETWORK, TIMEOUT, STORAGE, AUTHENTICATION, FORBIDDEN, VALIDATION, RATE_LIMITED, PROTOCOL, EXPIRED_CREATE, UPLOAD_UNKNOWN }
enum class DraftCommandKind { CREATE, PATCH }
enum class DraftResolution { REMOTE, LOCAL, MERGED }

data class DraftConflict(val base:EventFields,val local:EventFields,val remote:EventFields)
data class LocalDraftState(val state:DraftSyncState,val localRevision:Long=0,val conflict:DraftConflict?=null,
    val problem:DraftProblem?=null,val retryAt:Instant?=null,val pendingCover:Boolean=false,val terminal:Boolean=false)
data class LocalDraftSummary(val id:DraftId,val remoteId:EventId?,val title:String,val state:DraftSyncState)

/** Exact immutable command persisted before HTTP; no bearer, session stamp or registration data. */
data class StoredDraftCommand(val kind:DraftCommandKind,val id:String,val body:String,val fingerprint:String,
    val fields:EventFields,val revision:Long,val firstAttemptAt:Instant?=null)

/** Local persistence counters are not a server revision or an authentication proof. */
data class DraftLease(val accountId:UserId,val generation:Long)
data class StoredDraft(
    val accountId:UserId,val id:DraftId,val incarnation:String,
    val storageVersion:Long=1,val localRevision:Long=1,
    val remoteId:EventId?=null,val slug:EventSlug?=null,
    val confirmed:EventFields?=null,val base:EventFields?=null,val pending:EventFields?=null,
    val state:DraftSyncState=DraftSyncState.LOCAL,val command:StoredDraftCommand?=null,
    val remoteConflict:EventFields?=null,val problem:DraftProblem?=null,
    val invalidFields:Set<String> = emptySet(),val retryAt:Instant?=null,
    val serverStatus:EventLifecycle?=null,val finishedAt:Instant?=null,val uploadedCover:EventCover?=null,
)

/** Narrow account-scoped storage. Every write is fenced and compares record incarnation/version. */
interface DraftStore {
    suspend fun activate(accountId:UserId):DraftLease
    suspend fun read(lease:DraftLease,id:DraftId):StoredDraft?
    suspend fun findRemote(lease:DraftLease,id:EventId):StoredDraft?
    fun observe(lease:DraftLease):Flow<List<StoredDraft>>
    suspend fun replace(lease:DraftLease,previous:StoredDraft?,next:StoredDraft):Boolean
    suspend fun delete(lease:DraftLease,previous:StoredDraft):Boolean
    suspend fun purge(accountId:UserId):List<String>
    suspend fun retainedCovers():Set<String>
}
