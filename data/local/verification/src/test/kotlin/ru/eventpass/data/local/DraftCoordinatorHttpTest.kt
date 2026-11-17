package ru.eventpass.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.remote.*

/** The production coordinator, actual Room/SQLite file and actual HTTP transport together. */
class DraftCoordinatorHttpTest {
    private class Fixture:AutoCloseable {
        val http=M09ProtocolFixture()
        val directory=Files.createTempDirectory("eventpass-coordinator")
        val path=directory.resolve("drafts.db").toString()
        var db=open()
        var store=RoomDraftStore(db)
        val auth=http.auth {store.purge(it)}
        val reader=object:CoverAssetReader {
            override suspend fun read(cover:EventCover)=byteArrayOf(1,2,3,4)
            override suspend fun release(cover:EventCover){}
        }
        fun open()=Room.databaseBuilder<DraftDatabase>(name=path).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        fun repo(clock:java.time.Clock=java.time.Clock.systemUTC())=LocalDraftRepository(http.api,auth,RemoteOrganizerRepository(http.api,auth,M09ProtocolFixture.unavailable),store,RemoteCoverTransfer(http.api,auth,reader),reader,clock)
        // Inspect persisted bytes without activating a second owner and revoking the coordinator lease.
        suspend fun row(id:DraftId)=DraftCodec.decode(db.drafts().read("actor",id.value)!!)
        fun reopen(){db.close();db=open();store=RoomDraftStore(db)}
        override fun close(){http.close();db.close();directory.toFile().deleteRecursively()}
    }
    private fun saved(result:EventEditResult<*>) {assertTrue(result.toString(),result is EventEditResult.Saved)}

    @Test fun expiredUnknownCreateRemainsDurableWithoutAnotherPost()=runBlocking {
        Fixture().use {f->
            f.http.login(f.auth);val at=java.time.Instant.parse("2026-11-17T00:00:00Z")
            val clock=java.time.Clock.fixed(at,java.time.ZoneOffset.UTC);val repo=f.repo(clock);val id=DraftId("local");repo.beginDraft(id)
            saved(repo.saveDraft(id,null,EventFields(title="Unknown")));f.http.failCreateAck=true
            assertTrue(repo.syncDraft(id) is EventEditResult.Failed)
            val command=f.row(id).command!!;f.reopen()
            saved(f.repo(java.time.Clock.offset(clock,java.time.Duration.ofDays(2))).syncDraft(id))
            assertEquals(1,f.http.creates.get());assertEquals(command,f.row(id).command)
            assertEquals(DraftProblem.EXPIRED_CREATE,f.row(id).problem)
        }
    }
    @Test fun coldRecoveryHidesButDoesNotPurgePendingAndReauthRestoresIt()=runBlocking {
        Fixture().use {f->
            f.http.login(f.auth);val repo=f.repo();val id=DraftId("local");repo.beginDraft(id)
            saved(repo.saveDraft(id,null,EventFields(title="Private pending")));val row=f.row(id)
            val cold=f.http.auth();f.http.intercept={r->if(r.url.encodedPath=="/api/me") M09ProtocolFixture.json("{}",503) else null}
            cold.refreshSession();assertTrue(cold.currentSession().state is ru.eventpass.core.domain.auth.SessionState.Recovery)
            val restored=LocalDraftRepository(f.http.api,cold,RemoteOrganizerRepository(f.http.api,cold,M09ProtocolFixture.unavailable),f.store,RemoteCoverTransfer(f.http.api,cold,f.reader),f.reader)
            assertEquals(EventEditResult.Failed(EventEditError.AuthenticationRequired),restored.syncDraft(id));assertEquals(row,f.row(id))
            f.http.intercept=null;cold.refreshSession();saved(restored.syncDraft(id));assertEquals("Private pending",f.row(id).confirmed!!.title)
        }
    }

    @Test fun localSaveReopenAndLostCreateAckReuseExactCommand()=runBlocking {
        Fixture().use {f->
            f.http.login(f.auth);val id=DraftId("local");var repo=f.repo();repo.beginDraft(id)
            saved(repo.saveDraft(id,null,EventFields(title="Unicode 李")))
            val command=f.row(id).command!!
            assertNull(command.firstAttemptAt);assertEquals(0,f.http.creates.get())
            f.reopen();repo=f.repo();assertEquals(command,f.row(id).command)
            f.http.failCreateAck=true
            assertTrue(repo.syncDraft(id) is EventEditResult.Failed)
            val attempted=f.row(id).command!!;assertNotNull(attempted.firstAttemptAt)
            f.reopen();repo=f.repo();saved(repo.syncDraft(id))
            assertEquals(1,f.http.creates.get());assertNull(f.row(id).command)
            val posts=f.http.requests.filter {it.first=="POST /api/events"}
            assertEquals(2,posts.size);assertEquals(command.body,posts[0].second);assertEquals(posts[0].second,posts[1].second)
            assertEquals("Unicode 李",f.row(id).confirmed!!.title)
            assertNull(f.store.read(f.store.activate(UserId("other-account")),id))
        }
    }
    @Test fun ackAWhileNewerBWasSavedCannotEraseBAndUnknownPatchIsReadBeforeRetry()=runBlocking {
        Fixture().use {f->
            f.http.login(f.auth);val repo=f.repo();val id=DraftId("local");repo.beginDraft(id)
            saved(repo.saveDraft(id,null,EventFields(title="A")))
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.http.cleanup+={release.countDown()}
            f.http.onCreate={entered.countDown();check(release.await(15,TimeUnit.SECONDS))}
            val sync=async(Dispatchers.Default){repo.syncDraft(id)}
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            saved(repo.saveDraft(id,null,EventFields(title="B")))
            f.http.failNextRead=1;release.countDown();sync.await()
            assertEquals("A",f.row(id).confirmed!!.title);assertEquals("B",f.row(id).pending!!.title)
            f.http.onCreate={};f.http.failPatchAck=true
            assertTrue(repo.syncDraft(id) is EventEditResult.Failed)
            assertEquals(DraftCommandKind.PATCH,f.row(id).command!!.kind)
            f.reopen();saved(f.repo().syncDraft(id))
            assertEquals(1,f.http.patches.get());assertNull(f.row(id).pending);assertEquals("B",f.row(id).confirmed!!.title)
        }
    }
    @Test fun actualRemoteConflictRequiresExplicitResolutionAndPreservesStableIds()=runBlocking {
        Fixture().use {f->
            f.http.login(f.auth);val repo=f.repo();val id=DraftId("local");repo.beginDraft(id)
            val original=EventFields(title="Base",questions=listOf(DraftQuestion("stable-q",help="Help")))
            saved(repo.saveDraft(id,null,original));saved(repo.syncDraft(id))
            saved(repo.saveDraft(id,null,original.copy(title="Local")))
            f.http.event=JsonObject(f.http.event+("title" to JsonPrimitive("Remote")))
            saved(repo.syncDraft(id));val conflict=f.row(id)
            assertEquals(DraftSyncState.CONFLICT,conflict.state);assertEquals("Base",conflict.base!!.title)
            assertEquals("Local",conflict.pending!!.title);assertEquals("Remote",conflict.remoteConflict!!.title)
            saved(repo.syncDraft(id));assertEquals(0,f.http.patches.get())
            saved(repo.resolveDraft(id,DraftResolution.LOCAL));saved(repo.syncDraft(id))
            assertEquals("Local",f.row(id).confirmed!!.title);assertEquals("stable-q",f.row(id).confirmed!!.questions.single().id)
        }
    }
    @Test fun uploadedDescriptorSurvivesReopenAndPurgedRowsCannotBeResurrected()=runBlocking {
        Fixture().use {f->
            f.http.login(f.auth);var repo=f.repo();val id=DraftId("local");repo.beginDraft(id)
            saved(repo.saveDraft(id,null,EventFields(title="Cover",cover=EventCover("managed","image/png",4))))
            f.http.coverByteCount=3 // Server-normalized asset size differs from the retained local file.
            f.http.onUpload={f.http.failNextRead=1}
            assertTrue(repo.syncDraft(id) is EventEditResult.Failed)
            assertEquals("asset-1",f.row(id).uploadedCover!!.remoteAssetId)
            assertEquals(4L,f.row(id).uploadedCover!!.byteCount)
            f.reopen();repo=f.repo();saved(repo.syncDraft(id));assertEquals(1,f.http.uploads.get())
            assertEquals("asset-1",f.row(id).confirmed!!.remoteCoverAssetId)
            saved(repo.saveDraft(id,null,EventFields(title="Late")))
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.http.cleanup+={release.countDown()}
            f.http.intercept={r->if(r.method=="PATCH") {entered.countDown();check(release.await(15,TimeUnit.SECONDS))};null}
            val sync=async(Dispatchers.Default){repo.syncDraft(id)};assertTrue(entered.await(10,TimeUnit.SECONDS))
            f.store.purge(UserId("actor"));release.countDown();sync.await()
            assertNull(f.store.read(f.store.activate(UserId("actor")),id))
        }
    }
}
