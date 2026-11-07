package ru.eventpass.data.fake.product

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*

class RefreshReadContractsTest {
    private class Storage: FakeAuthStorage {
        var data: ByteArray?=null;var writes=0
        override fun read()=data?.copyOf()
        override fun write(snapshot: ByteArray){writes++;data=snapshot.copyOf()}
    }
    @Test fun refreshReadsExistingProfileAndEventGrantsWithoutWritesOrMutations()=runTest {
        val disk=Storage();val e=FakeEventPassEnvironment(authStorage=disk,authPolicy=FakeAuthPolicy(passwordIterations=1000))
        e.scenarios.signInAs(FakeFixtures.HOST)
        val profile=e.profiles.observeProfile().first();val list=e.volunteers.observeVolunteers(FakeFixtures.MIXED).first()
        val writes=disk.writes;val snapshot=disk.data?.copyOf();val session=e.auth.currentSession()
        assertEquals(profile,e.profiles.refreshProfile());assertEquals(list,e.volunteers.refreshVolunteers(FakeFixtures.MIXED))
        assertEquals(VolunteersResult.Failed(VolunteerError.FORBIDDEN),e.volunteers.refreshVolunteers(FakeFixtures.OWNED))
        for(error in ProductReadError.entries) {
            e.scenarios.failNextRead(error)
            assertEquals(ProfileResult.Failed(ProfileError.valueOf(error.name)),e.profiles.refreshProfile())
            assertEquals(profile,e.profiles.refreshProfile())
            e.scenarios.failNextRead(error)
            assertEquals(VolunteersResult.Failed(VolunteerError.valueOf(error.name)),e.volunteers.refreshVolunteers(FakeFixtures.MIXED))
            assertEquals(list,e.volunteers.refreshVolunteers(FakeFixtures.MIXED))
        }
        assertEquals(writes,disk.writes);assertArrayEquals(snapshot,disk.data);assertEquals(session,e.auth.currentSession())
        assertEquals(list,e.volunteers.observeVolunteers(FakeFixtures.MIXED).first())
        e.auth.signOut()
        assertEquals(ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED),e.profiles.refreshProfile())
        assertEquals(VolunteersResult.Failed(VolunteerError.AUTHENTICATION_REQUIRED),e.volunteers.refreshVolunteers(FakeFixtures.MIXED))
    }
    @Test fun delayedProfileReadRejectsReplacedSessionAndCancellationDoesNotWrite()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val disk=Storage()
        val e=FakeEventPassEnvironment(authStorage=disk,authPolicy=FakeAuthPolicy(passwordIterations=1000),
            beforeProfileDocumentOperation={if(it=="profile-read"){entered.complete(Unit);release.await()}})
        e.scenarios.signInAs(FakeFixtures.ALEX)
        val read=async {e.profiles.refreshProfile()};entered.await();e.scenarios.signInAs(FakeFixtures.HOST);val writes=disk.writes
        release.complete(Unit)
        assertEquals(ProfileResult.Failed(ProfileError.SESSION_CHANGED),read.await());assertEquals(writes,disk.writes)
        val cancelled=launch(start=CoroutineStart.LAZY){e.profiles.refreshProfile()};cancelled.cancel();cancelled.join()
        assertEquals(writes,disk.writes)
    }
}
