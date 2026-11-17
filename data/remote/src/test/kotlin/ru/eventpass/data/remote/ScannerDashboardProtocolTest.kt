package ru.eventpass.data.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*

class ScannerDashboardProtocolTest {
    private val id=EventId("server-event")
    @Test fun searchCacheIsBoundedWhileAnObservedPageRemainsUsable()=runBlocking {
        M09ProtocolFixture().use {f->
            f.published();f.populate(25);val auth=f.auth();f.login(auth)
            val repo=RemoteAttendanceRepository(f.api,auth,RemoteOrganizerRepository(f.api,auth,M09ProtocolFixture.unavailable),RemoteVolunteersRepository(f.api,auth),RemoteTickets(f.api,auth),RemoteCheckInRepository(f.api,auth))
            val first=CompletableDeferred<DashboardResult.Ready>()
            val observer=launch {repo.observeDashboard(id).collect {if(it is DashboardResult.Ready) first.complete(it)}}
            try {
                val active=withTimeout(5000){first.await()}
                repeat(40) {assertTrue(repo.refreshDashboard(id,"query-$it") is DashboardResult.Ready)}
                val retained=(repo.javaClass.getDeclaredField("entries").apply {isAccessible=true}.get(repo) as Map<*,*>).size
                assertTrue("Retained $retained query projections",retained<=33)
                assertTrue(repo.attendees(id,"",active.firstPage!!.next) is AttendeesResult.Page)
            } finally {observer.cancelAndJoin()}
        }
    }
    @Test fun sparseScanLostAckReplayUsedInvalidAndGrantRevokeUseRealUserId()=runBlocking {
        M09ProtocolFixture().use {f->
            f.published();val auth=f.auth();f.login(auth);val scanner=RemoteCheckInRepository(f.api,auth)
            scanner.refreshAccess(id)
            val access=scanner.observeAccess(id).first() as ScannerAccess.Allowed
            val command=ScanCommand(TicketCredential.Manual(ManualCode("EVT-ABC123")),scope=access.scope)
            f.failScanAck=true
            assertTrue((scanner.checkIn(id,command) as CheckInOutcome.Failed).uncertain)
            val replay=scanner.checkIn(id,command) as CheckInOutcome.Confirmed
            assertTrue(replay.replayed);assertEquals(ScanDecision.ALLOWED,replay.decision);assertNull(replay.receipt.checkedInBy)
            assertEquals(1,f.scans.get())
            assertEquals(ScanDecision.ALREADY_USED,(scanner.checkIn(id,ScanCommand(command.credential)) as CheckInOutcome.Confirmed).decision)
            assertEquals(ScanDecision.INVALID,(scanner.checkIn(id,ScanCommand(TicketCredential.Qr(QrToken("invalid")))) as CheckInOutcome.Confirmed).decision)
            assertNull(scanner.parseManual("arbitrary-secret"));assertNotNull(scanner.parseManual("evt-abc123"))
            val volunteers=RemoteVolunteersRepository(f.api,auth)
            assertTrue(volunteers.refreshVolunteers(id) is VolunteersResult.Ready)
            val granted=volunteers.grant(id,"654321") as VolunteerOutcome.Granted
            assertEquals("654321",granted.volunteer.publicId)
            assertEquals(VolunteerOutcome.Revoked(UserId("volunteer-user")),volunteers.revoke(id,granted.volunteer.grant.userId))
            assertTrue(f.requests.any {it.first=="DELETE /api/events/server-event/volunteers/volunteer-user"})
            assertTrue((volunteers.refreshVolunteers(id) as VolunteersResult.Ready).volunteers.isEmpty())
        }
    }
    @Test fun oldScannerGetCannotRollbackCountAfterConfirmedScan()=runBlocking {
        M09ProtocolFixture().use {f->
            f.published();val auth=f.auth();f.login(auth);val scanner=RemoteCheckInRepository(f.api,auth);scanner.refreshAccess(id)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.cleanup+={release.countDown()};val first=AtomicBoolean(true)
            f.intercept={r->if(r.url.encodedPath.endsWith("/scanner") && first.compareAndSet(true,false)) {
                entered.countDown();check(release.await(15,TimeUnit.SECONDS))
                M09ProtocolFixture.json(buildJsonObject {put("event",f.event);put("viewerRole","organizer");put("checkedByMe",0);put("recent",buildJsonArray {})})
            } else null}
            val old=async(Dispatchers.Default){scanner.refreshAccess(id)};assertTrue(entered.await(10,TimeUnit.SECONDS))
            val scan=async(Dispatchers.Default){scanner.checkIn(id,ScanCommand(TicketCredential.Qr(QrToken("one-ticket"))))}
            // Confirm the POST has reached the server before releasing the old snapshot.
            withTimeout(5000){while(f.scans.get()!=1) delay(5)}
            // Let an implementation that skips the occupied reader finish before the stale GET.
            // A correct serial post-mutation read may wait; it is released below in either case.
            withTimeoutOrNull(1000) {scan.await()}
            release.countDown();assertTrue(scan.await() is CheckInOutcome.Confirmed);old.await()
            val final=scanner.observeAccess(id).first() as ScannerAccess.Allowed
            assertEquals(1,final.info!!.checkedByMe)
        }
    }
    @Test fun coherentRevisionServerPagesSearchBoundsAndUsedCancellation()=runBlocking {
        M09ProtocolFixture().use {f->
            f.published();f.populate(25)
            val auth=f.auth();f.login(auth)
            val scanner=RemoteCheckInRepository(f.api,auth);val volunteers=RemoteVolunteersRepository(f.api,auth)
            val repo=RemoteAttendanceRepository(f.api,auth,RemoteOrganizerRepository(f.api,auth,M09ProtocolFixture.unavailable),volunteers,RemoteTickets(f.api,auth),scanner)
            val ready=repo.refreshDashboard(id) as DashboardResult.Ready
            assertEquals(25,ready.dashboard.summary.registered);assertEquals(20,ready.firstPage!!.rows.size)
            val page=repo.attendees(id,"",ready.firstPage!!.next) as AttendeesResult.Page
            assertEquals(5,page.rows.size);assertEquals(ready.dashboard.revision,page.revision)
            val filtered=repo.refreshDashboard(id,"Person 24") as DashboardResult.Ready
            assertEquals(25,filtered.dashboard.summary.registered);assertEquals(1,filtered.firstPage!!.rows.size)
            assertTrue(repo.attendees(id,"different",ready.firstPage!!.next) is AttendeesResult.Failed)
            val before=f.server.requestCount
            assertEquals(DashboardResult.Failed(AttendanceError.INVALID_INPUT),repo.refreshDashboard(id,"x".repeat(301)))
            assertEquals(before,f.server.requestCount)
            f.stalePages=5
            assertEquals(DashboardResult.Failed(AttendanceError.STALE_PAGE),repo.refreshDashboard(id))
            assertEquals(3,f.stalePages) // Exactly two bounded snapshot attempts.
            f.stalePages=0
            f.rows[0]=JsonObject(f.rows[0]+("status" to JsonPrimitive("present")))
            assertTrue(repo.cancelTicket(id,TicketId("ticket-0")) is OwnerCancelResult.Failed)
            assertTrue(repo.cancelTicket(id,TicketId("ticket-1")) is OwnerCancelResult.Cancelled)
            assertTrue(f.requests.any {it.first=="POST /api/events/server-event/attendees/ticket-1/cancel"})
        }
    }
}
