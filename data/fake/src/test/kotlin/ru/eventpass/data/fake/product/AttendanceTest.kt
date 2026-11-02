package ru.eventpass.data.fake.product

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.event.*
import java.io.IOException
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AttendanceTest {
    private fun environment() = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000))
    private suspend fun dashboard(e: FakeEventPassEnvironment, id: EventId = FakeFixtures.MIXED) =
        (e.attendance.observeDashboard(id).first() as DashboardResult.Ready).dashboard
    private suspend fun guest(e: FakeEventPassEnvironment, number: Int, id: EventId = FakeFixtures.OWNED) =
        e.registrations.submitGuest(GuestRegistrationCommand(id,1,"Guest $number","guest$number@example.com",consentAccepted = true)) as GuestRegistrationOutcome.Issued
    private class Storage : FakeAuthStorage {
        var bytes: ByteArray? = null; var fail = false; var blocking = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        override fun read() = bytes?.copyOf()
        override fun write(snapshot: ByteArray) {
            if (blocking) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            if (fail) throw IOException("Fixture failure")
            bytes = snapshot.copyOf()
        }
    }
    @Test fun sharedDashboardCheckInAndOwnTicketObserveOneMutation() = runTest {
        val e = environment(); e.scenarios.signInAs(FakeFixtures.HOST)
        val before = (e.attendance.observeDashboard(FakeFixtures.MIXED).first() as DashboardResult.Ready).dashboard
        assertEquals(AttendanceSummary(1,0,1,19), before.summary)
        assertTrue(e.checkIns.checkIn(FakeFixtures.MIXED, TicketCredential.Qr(QrToken("demo-token-alex-mixed"))) is CheckInOutcome.Admitted)
        val after = (e.attendance.observeDashboard(FakeFixtures.MIXED).first() as DashboardResult.Ready).dashboard
        assertEquals(AttendanceSummary(1,1,0,19), after.summary); assertNotEquals(before.revision, after.revision)
        val row = (e.attendance.attendees(FakeFixtures.MIXED, "") as AttendeesResult.Page).rows.single()
        assertEquals(TicketStatus.USED,row.status); assertEquals(FakeFixtures.HOST,row.checkerId)
        e.scenarios.signInAs(FakeFixtures.ALEX)
        val mine = e.tickets.observeMyTickets().first() as MyTicketsResult.Ready
        assertEquals(TicketStatus.USED,mine.tickets.single { it.snapshot.eventId == FakeFixtures.MIXED }.status)
    }
    @Test fun ownerOnlyOnObservationPageRefreshAndForgedRegistration() = runTest {
        val e = environment()
        for (actor in listOf(null, FakeFixtures.ALEX)) {
            if (actor == null) e.auth.signOut() else e.scenarios.signInAs(actor)
            val error = if (actor == null) AttendanceError.AUTHENTICATION_REQUIRED else AttendanceError.FORBIDDEN
            assertEquals(DashboardResult.Failed(error),e.attendance.observeDashboard(FakeFixtures.MIXED).first())
            assertEquals(DashboardResult.Failed(error),e.attendance.refreshDashboard(FakeFixtures.MIXED))
            assertEquals(AttendeesResult.Failed(error),e.attendance.attendees(FakeFixtures.MIXED,""))
            assertEquals(OwnerCancelResult.Failed(error),e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed"))
        }
        // Alex owns another E and has a grant + participation in MIXED: still no dashboard access.
        assertTrue(e.attendance.observeDashboard(FakeFixtures.OWNED).first() is DashboardResult.Ready)
        for (id in listOf(FakeFixtures.ATTENDING,FakeFixtures.VOLUNTEER)) {
            assertEquals(DashboardResult.Failed(AttendanceError.FORBIDDEN),e.attendance.observeDashboard(id).first())
            assertEquals(AttendeesResult.Failed(AttendanceError.FORBIDDEN),e.attendance.attendees(id,""))
        }
        assertEquals(OwnerCancelResult.Failed(AttendanceError.NOT_FOUND),e.attendance.cancelRegistration(FakeFixtures.OWNED,"seed-registration-alex-mixed"))
        e.scenarios.signInAs(FakeFixtures.HOST)
        val cursor = AttendanceCursor(FakeFixtures.ATTENDING,dashboard(e).revision,"",0)
        assertEquals(AttendeesResult.Failed(AttendanceError.STALE_PAGE),e.attendance.attendees(FakeFixtures.MIXED,"",cursor))
        e.scenarios.putAccount(Account(FakeFixtures.HOST,"host@example.com",false))
        assertEquals(DashboardResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED),e.attendance.observeDashboard(FakeFixtures.MIXED).first())
    }
    @Test fun guestAndAuthenticatedRegistrationShareTotalsAndUnlimitedZeroPercent() = runTest {
        val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        val event = FakeFixtures.events().single { it.id == FakeFixtures.OWNED }; e.scenarios.putEvent(event.copy(capacity = null))
        assertEquals(AttendanceSummary(0,0,0,null),dashboard(e,FakeFixtures.OWNED).summary)
        assertEquals(0,dashboard(e,FakeFixtures.OWNED).summary.percent)
        guest(e,1)
        e.registrations.submitAuthenticated(AuthenticatedRegistrationCommand(event.id,1,"Owner",consentAccepted = true))
        assertEquals(AttendanceSummary(2,0,2,null),dashboard(e,event.id).summary)
    }
    @Test fun ownerAndSelfCancelOnceShareStatusCapacityAndPermanentSchemaLock() = runTest {
        val e = environment(); e.scenarios.signInAs(FakeFixtures.HOST)
        val row = (e.attendance.attendees(FakeFixtures.MIXED,"") as AttendeesResult.Page).rows.single()
        repeat(2) { assertEquals(OwnerCancelResult.Cancelled,e.attendance.cancelRegistration(FakeFixtures.MIXED,row.registrationId)) }
        assertEquals(AttendanceSummary(0,0,0,20),dashboard(e).summary)
        assertTrue((e.events.observeEditor(EventEditorTarget.Published(FakeFixtures.MIXED)).first() as EventEditorResult.Published).questionsLocked)
        e.scenarios.signInAs(FakeFixtures.ALEX)
        repeat(2) { assertTrue(e.tickets.cancelOwnTicket(row.ticketId) is CancelTicketOutcome.Cancelled) }
        val own = e.tickets.observeMyTickets().first() as MyTicketsResult.Ready
        assertEquals(TicketStatus.CANCELLED,own.tickets.single { it.snapshot.ticketId == row.ticketId }.status)
    }
    @Test fun cancelCheckInRaceSerializesAndUsedCannotBeCancelled() = runTest {
        repeat(8) {
            val e = environment(); e.scenarios.signInAs(FakeFixtures.HOST)
            val results = coroutineScope {
                val scan = async(Dispatchers.Default) { e.checkIns.checkIn(FakeFixtures.MIXED,TicketCredential.Qr(QrToken("demo-token-alex-mixed"))) }
                val cancel = async(Dispatchers.Default) { e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed") }
                scan.await() to cancel.await()
            }
            if (results.first is CheckInOutcome.Admitted) {
                assertEquals(OwnerCancelResult.Failed(AttendanceError.USED),results.second)
                assertEquals(AttendanceSummary(1,1,0,19),dashboard(e).summary)
            } else {
                assertEquals(CheckInOutcome.Invalid(InvalidTicketReason.CANCELLED),results.first)
                assertEquals(OwnerCancelResult.Cancelled,results.second)
                assertEquals(AttendanceSummary(0,0,0,20),dashboard(e).summary)
            }
        }
    }
    @Test fun searchAndRevisionPagesNeverChangeTotalsOrExposeCredentials() = runTest {
        val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        e.scenarios.putEvent(FakeFixtures.events().single { it.id == FakeFixtures.OWNED }.copy(capacity = null))
        val issued = (1..43).map { guest(e,it) }
        val before = dashboard(e,FakeFixtures.OWNED)
        val ids = mutableListOf<TicketId>(); var cursor: AttendanceCursor? = null
        do {
            val page = e.attendance.attendees(FakeFixtures.OWNED,"  gUeSt  ",cursor) as AttendeesResult.Page
            assertEquals(43,page.totalMatches); assertTrue(page.rows.size <= 20)
            ids += page.rows.map { it.ticketId }; cursor = page.next
        } while(cursor != null)
        assertEquals(43,ids.size); assertEquals(ids.size,ids.distinct().size)
        assertEquals(before.summary,dashboard(e,FakeFixtures.OWNED).summary)
        assertEquals(0,(e.attendance.attendees(FakeFixtures.OWNED,issued.first().ticket.qrPayload) as AttendeesResult.Page).totalMatches)
        assertEquals(1,(e.attendance.attendees(FakeFixtures.OWNED,issued.last().ticket.displayNumber) as AttendeesResult.Page).totalMatches)
        val first = e.attendance.attendees(FakeFixtures.OWNED,"") as AttendeesResult.Page
        e.checkIns.checkIn(FakeFixtures.OWNED,TicketCredential.Manual(issued.first().ticket.manualCode))
        assertEquals(AttendeesResult.Failed(AttendanceError.STALE_PAGE),e.attendance.attendees(FakeFixtures.OWNED,"",first.next))
        assertEquals(AttendanceSummary(43,1,42,null),dashboard(e,FakeFixtures.OWNED).summary)
    }
    @Test fun legacyUsedAndVolunteerCounterUseRealRecordsNotAttempts() = runTest {
        val e = environment(); e.scenarios.signInAs(FakeFixtures.ALEX)
        e.scenarios.setTicketStatus(TicketId("seed-ticket-alex-attending"),TicketStatus.USED)
        e.checkIns.checkIn(FakeFixtures.MIXED,TicketCredential.Qr(QrToken("demo-token-alex-mixed")))
        repeat(2) { e.checkIns.checkIn(FakeFixtures.MIXED,TicketCredential.Qr(QrToken("demo-token-alex-mixed"))) }
        e.scenarios.signInAs(FakeFixtures.HOST)
        assertEquals(1,(e.volunteers.observeVolunteers(FakeFixtures.MIXED).first() as VolunteersResult.Ready).volunteers.single().checkedInCount)
        e.volunteers.revoke(FakeFixtures.MIXED,FakeFixtures.ALEX); e.volunteers.grant(FakeFixtures.MIXED,"alex@example.com")
        assertEquals(1,(e.volunteers.observeVolunteers(FakeFixtures.MIXED).first() as VolunteersResult.Ready).volunteers.single().checkedInCount)
        assertEquals(1,dashboard(e,FakeFixtures.ATTENDING).summary.checkedIn)
        val row=(e.attendance.attendees(FakeFixtures.ATTENDING,"") as AttendeesResult.Page).rows.single()
        assertNull(row.checkedAt); assertNull(row.checkerId)
    }
    @Test fun failedStorageAndTransportKeepStatusAndRestartRetainsCommittedCancellation() = runTest {
        val storage = Storage(); val e = FakeEventPassEnvironment(authStorage=storage,authPolicy=FakeAuthPolicy(passwordIterations=1000))
        e.scenarios.signInAs(FakeFixtures.HOST)
        val before=dashboard(e); storage.fail=true
        assertEquals(OwnerCancelResult.Failed(AttendanceError.STORAGE_UNAVAILABLE),e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed"))
        assertEquals(before.summary,dashboard(e).summary); storage.fail=false
        e.scenarios.failNextCancellation(TicketError.NETWORK)
        assertEquals(OwnerCancelResult.Failed(AttendanceError.NETWORK),e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed"))
        e.scenarios.failNextAttendanceRead(AttendanceError.TIMEOUT)
        assertEquals(DashboardResult.Failed(AttendanceError.TIMEOUT),e.attendance.refreshDashboard(FakeFixtures.MIXED))
        assertEquals(AttendeesResult.Failed(AttendanceError.TIMEOUT),e.attendance.attendees(FakeFixtures.MIXED,""))
        e.attendance.refreshDashboard(FakeFixtures.MIXED)
        e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed")
        val next=FakeEventPassEnvironment(authStorage=storage,authPolicy=FakeAuthPolicy(passwordIterations=1000)); next.scenarios.signInAs(FakeFixtures.HOST)
        assertEquals(AttendanceSummary(0,0,0,20),dashboard(next).summary)
        assertTrue((next.events.observeEditor(EventEditorTarget.Published(FakeFixtures.MIXED)).first() as EventEditorResult.Published).questionsLocked)
    }
    @Test fun queuedCancellationChecksNewSessionAndCancelsWithoutBlockingCaller() = runBlocking {
        val storage=Storage(); val e=FakeEventPassEnvironment(authStorage=storage,authPolicy=FakeAuthPolicy(passwordIterations=1000))
        e.scenarios.signInAs(FakeFixtures.HOST); storage.blocking=true
        val blocker=async(Dispatchers.IO) { e.volunteers.grant(FakeFixtures.MIXED,"host@example.com") }
        try {
            assertTrue(storage.entered.await(3,TimeUnit.SECONDS))
            val cancelled=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) { e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed") }
            cancelled.cancel(); withTimeout(1000) { cancelled.join() }
            val signout=async(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) { e.auth.signOut() }
            val stale=async(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) { e.attendance.cancelRegistration(FakeFixtures.MIXED,"seed-registration-alex-mixed") }
            storage.blocking=false; storage.release.countDown(); blocker.await(); signout.await()
            assertEquals(OwnerCancelResult.Failed(AttendanceError.SESSION_CHANGED),stale.await())
            e.scenarios.signInAs(FakeFixtures.HOST); assertEquals(1,dashboard(e).summary.registered)
        } finally { storage.release.countDown(); blocker.cancel() }
    }
    @Test fun startsAtDoesNotFinishEventAndExpiredOwnerLosesEveryRead() = runTest {
        class Time(var now: Instant=FakeFixtures.NOW) : Clock() {
            override fun instant()=now
            override fun getZone(): ZoneId=ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock=Clock.fixed(now,zone)
        }
        val events=Time(); val auth=Time()
        val e=FakeEventPassEnvironment(clock=events,authClock=auth,authPolicy=FakeAuthPolicy(passwordIterations=1000))
        e.scenarios.signInAs(FakeFixtures.HOST)
        val event=FakeFixtures.events().single { it.id==FakeFixtures.MIXED }
        events.now=event.startsAt.plusSeconds(1); assertFalse(dashboard(e).header.finished)
        events.now=event.endsAt!!; assertTrue(dashboard(e).header.finished)
        auth.now=auth.now.plusSeconds(1801)
        assertEquals(DashboardResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED),e.attendance.observeDashboard(event.id).first())
        assertEquals(DashboardResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED),e.attendance.refreshDashboard(event.id))
        assertEquals(AttendeesResult.Failed(AttendanceError.AUTHENTICATION_REQUIRED),e.attendance.attendees(event.id,""))
    }
}
