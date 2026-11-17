package ru.eventpass.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.event.*

class RoomDraftStore(private val database:DraftDatabase):DraftStore {
    private val dao=database.drafts()
    override suspend fun activate(accountId:UserId)=DraftLease(accountId,dao.activate(accountId.value))
    override suspend fun read(lease:DraftLease,id:DraftId)=withContext(Dispatchers.Default) {
        dao.readActive(lease.accountId.value,lease.generation,id.value)?.let(DraftCodec::decode)
    }
    override suspend fun findRemote(lease:DraftLease,id:EventId)=withContext(Dispatchers.Default) {
        dao.readActive(lease.accountId.value,lease.generation,id.value,true)?.let(DraftCodec::decode)
    }
    override fun observe(lease:DraftLease)=dao.observe(lease.accountId.value,lease.generation).map {rows->rows.map(DraftCodec::decode)}.flowOn(Dispatchers.Default)
    override suspend fun replace(lease:DraftLease,previous:StoredDraft?,next:StoredDraft)=withContext(Dispatchers.Default) {
        require(next.accountId==lease.accountId && (previous==null || previous.accountId==next.accountId && previous.id==next.id))
        dao.replace(lease.accountId.value,lease.generation,previous?.storageVersion,previous?.incarnation,DraftCodec.encode(next))
    }
    override suspend fun delete(lease:DraftLease,previous:StoredDraft):Boolean {
        require(previous.accountId==lease.accountId)
        return dao.deleteActive(lease.accountId.value,lease.generation,previous.id.value,previous.incarnation,previous.storageVersion)
    }
    override suspend fun purge(accountId:UserId)=dao.purge(accountId.value).mapNotNull {it.coverReference}.distinct()
    override suspend fun retainedCovers()=dao.coverReferences().toSet()
}
