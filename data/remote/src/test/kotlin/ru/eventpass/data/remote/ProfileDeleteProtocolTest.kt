package ru.eventpass.data.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.UserId
import ru.eventpass.core.domain.profile.*

class ProfileDeleteProtocolTest {
    @Test fun confirmedDeletionRetiresNewSessionOfTheSameDeletedAccount()=runBlocking {
        M09ProtocolFixture().use {f->
            var purges=0;val auth=f.auth {purges++};f.login(auth)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.cleanup+={release.countDown()}
            f.intercept={r->if(r.method=="DELETE") {entered.countDown();check(release.await(15,TimeUnit.SECONDS));M09ProtocolFixture.empty()} else null}
            val deletion=async(Dispatchers.Default){auth.profileRepository.deleteAccount()}
            assertTrue(entered.await(10,TimeUnit.SECONDS));f.login(auth);release.countDown()
            assertTrue(deletion.await() is DeleteAccountResult.Deleted)
            assertTrue(auth.currentSession().state is SessionState.Guest);assertEquals(1,purges)
        }
    }
    @Test fun patchFalseNullAndEmailKeepsSameAccountSessionAndIgnoresOldGet()=runBlocking {
        M09ProtocolFixture().use {f->
            var purges=0;val auth=f.auth {purges++};f.login(auth)
            val old=(auth.currentSession().state as SessionState.Authenticated).session
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.cleanup+={release.countDown()}
            val oldJson=f.user
            f.intercept={r->if(r.method=="GET" && r.url.encodedPath=="/api/me") {entered.countDown();check(release.await(15,TimeUnit.SECONDS));M09ProtocolFixture.json(oldJson)} else null}
            val read=async(Dispatchers.Default){auth.profileRepository.refreshProfile()}
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            val ready=auth.profileRepository.observeProfile().first() as ProfileResult.Ready
            val result=auth.profileRepository.saveProfile(ready.profile.fields.copy(email="changed@example.test",emailNotifications=false,phone=null,organization=null))
            assertTrue(result.toString(),result is ProfileResult.Ready)
            release.countDown();read.await()
            val now=(auth.currentSession().state as SessionState.Authenticated).session
            assertEquals(old.id,now.id);assertEquals(old.account.id,now.account.id);assertEquals("changed@example.test",now.account.email);assertEquals(0,purges)
            val patch=Json.parseToJsonElement(f.requests.single {it.first=="PATCH /api/me"}.second).jsonObject
            assertEquals(setOf("email","notificationsEnabled"),patch.keys);assertEquals(false,patch["notificationsEnabled"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun uncertainDeleteIsNotQueuedAnd401IsNeverReportedAsDeleted()=runBlocking {
        M09ProtocolFixture().use {f->
            var purges=0;val auth=f.auth {purges++};f.login(auth)
            f.intercept={r->if(r.method=="DELETE") M09ProtocolFixture.json("{}",503) else null}
            assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Uncertain)
            assertTrue(auth.currentSession().state is SessionState.Authenticated);assertEquals(0,purges)
            val before=f.server.requestCount
            assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Uncertain);assertEquals(before,f.server.requestCount)
            assertTrue(auth.profileRepository.reconcileDeletion() is DeleteAccountResult.Present)
            f.intercept={r->if(r.method=="DELETE") M09ProtocolFixture.json("""{"code":"UNAUTHORIZED"}""",401) else null}
            assertEquals(DeleteAccountResult.AccessRevoked,auth.profileRepository.deleteAccount());assertEquals(0,purges)
            f.login(auth);f.intercept=null
            assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Deleted)
            assertTrue(auth.currentSession().state is SessionState.Guest);assertEquals(1,purges);assertNull(f.secret)
        }
    }
    @Test fun lateConfirmedDeletePurgesOnlyOldAccountAndKeepsDifferentActor()=runBlocking {
        M09ProtocolFixture().use {f->
            val purged=mutableListOf<UserId>();val auth=f.auth {purged+=it};f.login(auth)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.cleanup+={release.countDown()}
            f.intercept={r->if(r.method=="DELETE") {entered.countDown();check(release.await(15,TimeUnit.SECONDS));M09ProtocolFixture.empty()} else null}
            val deletion=async(Dispatchers.Default){auth.profileRepository.deleteAccount()}
            assertTrue(entered.await(10,TimeUnit.SECONDS));f.user=M09ProtocolFixture.user("other","other@example.test");f.login(auth)
            release.countDown();deletion.await()
            assertEquals(UserId("other"),(auth.currentSession().state as SessionState.Authenticated).session.account.id)
            assertEquals(listOf(UserId("actor")),purged)
        }
    }
    @Test fun lateFailedDeleteCannotMakeNewActorDeletionUncertain()=runBlocking {
        M09ProtocolFixture().use {f->
            val auth=f.auth();f.login(auth)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.cleanup+={release.countDown()}
            f.intercept={r->if(r.method=="DELETE") {entered.countDown();check(release.await(15,TimeUnit.SECONDS));M09ProtocolFixture.json("{}",503)} else null}
            val deletion=async(Dispatchers.Default){auth.profileRepository.deleteAccount()}
            assertTrue(entered.await(10,TimeUnit.SECONDS));f.user=M09ProtocolFixture.user("other","other@example.test");f.login(auth)
            release.countDown();deletion.await()
            val profile=auth.profileRepository.observeProfile().first() as ProfileResult.Ready
            assertFalse(profile.deletionUncertain)
            assertTrue(auth.profileRepository.saveProfile(profile.profile.fields.copy(name="Changed")) is ProfileResult.Ready)
        }
    }
}
