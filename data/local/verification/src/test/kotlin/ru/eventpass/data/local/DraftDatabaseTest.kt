package ru.eventpass.data.local

import androidx.room.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.event.*

class DraftDatabaseTest {
    private suspend fun <T> DraftDatabase.using(block:suspend (DraftDatabase)->T):T = try { block(this) } finally { close() }
    private fun open(path:String)=Room.databaseBuilder<DraftDatabase>(name=path)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

    @Test fun realRoomFileReopensNullableUnicodePendingAndMapping()=runTest {
        val directory=Files.createTempDirectory("eventpass-room-test")
        val path=directory.resolve("drafts.db").toString()
        val fields=EventFields(title="Неполный черновик 😀",cover=EventCover("managed-file","image/png",4),
            questions=listOf(DraftQuestion("stable-question",help="Подсказка")),sections=listOf(EventSection("","","stable-section")))
        val command=StoredDraftCommand(DraftCommandKind.CREATE,"immutable-key","immutable body","fingerprint",fields,3)
        val row=StoredDraft(UserId("account-a"),DraftId("local-id"),"incarnation",localRevision=3,pending=fields,command=command)
        try {
            open(path).using {db->val store=RoomDraftStore(db);val lease=store.activate(row.accountId);assertTrue(store.replace(lease,null,row))}
            open(path).using {db->
                val store=RoomDraftStore(db);val lease=store.activate(row.accountId)
                assertEquals(row,store.read(lease,row.id));assertNull(store.findRemote(lease,EventId("missing")))
                assertNull(store.read(store.activate(UserId("account-b")),row.id))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun actualTransactionRollsBackAndLateAckCannotResurrectDeletedRow()=runTest {
        val directory=Files.createTempDirectory("eventpass-room-rollback")
        try {open(directory.resolve("drafts.db").toString()).using {db->
            val dao=db.drafts();val row=DraftRow("account","id","incarnation",1)
            assertTrue(runCatching {
                db.useWriterConnection { writer -> writer.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {dao.insert(row);dao.insert(row)} }
            }.isFailure)
            assertNull(dao.read("account","id"))
            val store=RoomDraftStore(db);val lease=store.activate(UserId("account"))
            val original=StoredDraft(lease.accountId,DraftId("id"),"old",pending=EventFields(title="A"))
            assertTrue(store.replace(lease,null,original));store.purge(lease.accountId)
            assertFalse(store.replace(lease,original,original.copy(storageVersion=2,confirmed=EventFields(title="A"))))
            assertNull(dao.read("account","id"))
        }} finally {directory.toFile().deleteRecursively()}
    }
    @Test fun storageCasPreservesNewerPendingAndChecksIncarnation()=runTest {
        val directory=Files.createTempDirectory("eventpass-room-ack")
        try {open(directory.resolve("drafts.db").toString()).using {db->
            val store=RoomDraftStore(db);val lease=store.activate(UserId("account"))
            val a=StoredDraft(lease.accountId,DraftId("id"),"old",pending=EventFields(title="A"))
            assertTrue(store.replace(lease,null,a))
            val b=a.copy(storageVersion=2,localRevision=2,pending=EventFields(title="B"))
            assertTrue(store.replace(lease,a,b));assertFalse(store.replace(lease,a,a.copy(storageVersion=2,pending=null)))
            assertEquals(b,store.read(lease,a.id))
            assertTrue(store.delete(lease,b))
            val replacement=a.copy(incarnation="new",pending=EventFields(title="Replacement"))
            assertTrue(store.replace(lease,null,replacement))
            assertFalse(store.replace(lease,a,a.copy(storageVersion=2,pending=null)))
            assertEquals(replacement,store.read(lease,a.id))
        }} finally {directory.toFile().deleteRecursively()}
    }
}
