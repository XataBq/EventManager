package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

class CheckInTest {
    private class Storage : FakeAuthStorage {
        var bytes: ByteArray? = null; var fail = false
        override fun read() = bytes?.copyOf()
        override fun write(snapshot: ByteArray) { if (fail) throw IOException("Fixture failure"); bytes = snapshot.copyOf() }
    }
    private class Time(var now: Instant = FakeFixtures.NOW) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private val fixed = Clock.fixed(FakeFixtures.NOW, ZoneOffset.UTC)
    private fun backend(storage: Storage = Storage(), clock: Clock = fixed, authClock: Clock = fixed) =
        FakeEventPassEnvironment(clock, storage, FakeAuthPolicy(passwordIterations = 1000), authClock = authClock)
    private suspend fun own(e: FakeEventPassEnvironment) = (e.tickets.observeMyTickets().first() as MyTicketsResult.Ready)
        .tickets.single { it.snapshot.eventId == FakeFixtures.MIXED }
    private suspend fun ticket(e: FakeEventPassEnvironment): Ticket {
        e.scenarios.signInAs(FakeFixtures.ALEX); return own(e)
    }
    private fun qr(t: Ticket) = TicketCredential.Qr(QrToken(t.snapshot.qrPayload))
    private fun manual(t: Ticket) = TicketCredential.Manual(t.snapshot.manualCode)
    private fun seed(): FakeProductState {
        val events = FakeFixtures.events()
        return FakeProductState(FakeFixtures.accounts().associateBy { it.id }, events.associateBy { it.id },
            FakeFixtures.registrations(), FakeFixtures.tickets(events), FakeFixtures.grants())
    }

    @Test fun ownerManualAdmissionAndQrRepeatShareUsedTicketAndFirstRecord() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e); e.scenarios.signInAs(FakeFixtures.HOST)
        val first = e.checkIns.checkIn(FakeFixtures.MIXED, manual(t)) as CheckInOutcome.Admitted
        assertEquals(FakeFixtures.HOST, first.record.checkerId); assertEquals(FakeFixtures.NOW, first.record.checkedAt)
        assertEquals(CheckInOutcome.AlreadyUsed(first.record), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        e.volunteers.revoke(FakeFixtures.MIXED, FakeFixtures.ALEX)
        e.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(TicketStatus.USED, own(e).status)
        assertEquals(CancelTicketOutcome.Failed(TicketError.USED), e.tickets.cancelOwnTicket(t.snapshot.ticketId))
        val availability = (e.events.observePublicEvent(FakeFixtures.MIXED).first() as PublicEventResult.Found).availability
        assertEquals(AvailablePlaces.Limited(19), (availability as RegistrationAvailability.Open).places)
        val next = backend(storage); next.scenarios.signInAs(FakeFixtures.HOST)
        assertEquals(CheckInOutcome.AlreadyUsed(first.record), next.checkIns.checkIn(FakeFixtures.MIXED, manual(t)))
    }
    @Test fun grantIsOwnerOnlyAndRevokeUpdatesSharedListsWithoutDeletingParticipation() = runBlocking {
        val e = backend(); val t = ticket(e)
        assertEquals(VolunteerOutcome.Failed(VolunteerError.FORBIDDEN), e.volunteers.grant(FakeFixtures.MIXED, "alex"))
        assertEquals(VolunteersResult.Failed(VolunteerError.FORBIDDEN), e.volunteers.observeVolunteers(FakeFixtures.MIXED).first())
        e.scenarios.signInAs(FakeFixtures.HOST)
        val grant = e.volunteers.grant(FakeFixtures.OPEN, " ALEX@EXAMPLE.COM ")
        assertEquals(grant, e.volunteers.grant(FakeFixtures.OPEN, "alex"))
        assertEquals(1, (e.volunteers.observeVolunteers(FakeFixtures.OPEN).first() as VolunteersResult.Ready).volunteers.size)
        assertEquals(VolunteerOutcome.Failed(VolunteerError.ACCOUNT_NOT_FOUND), e.volunteers.grant(FakeFixtures.OPEN, "absent@example.com"))
        val revoked = e.volunteers.revoke(FakeFixtures.MIXED, FakeFixtures.ALEX)
        assertEquals(revoked, e.volunteers.revoke(FakeFixtures.MIXED, FakeFixtures.ALEX))
        e.scenarios.signInAs(FakeFixtures.ALEX)
        assertFalse((e.events.observeMyEvents().first() as MyEventsResult.Ready).volunteering.any { it.id == FakeFixtures.MIXED })
        assertEquals(t, own(e))
        assertEquals(CheckInOutcome.Failed(CheckInError.FORBIDDEN), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
    }
    @Test fun ownerNeedsNoSelfGrantAndParticipantCannotScanOtherEvent() = runBlocking {
        val e = backend(); val t = ticket(e)
        assertEquals(CheckInOutcome.Failed(CheckInError.FORBIDDEN), e.checkIns.checkIn(FakeFixtures.ATTENDING, qr(t)))
        e.volunteers.grant(FakeFixtures.OWNED, "alex")
        e.volunteers.revoke(FakeFixtures.OWNED, FakeFixtures.ALEX)
        assertTrue(e.checkIns.observeAccess(FakeFixtures.OWNED).first() is ScannerAccess.Allowed)
        e.scenarios.signOut()
        assertEquals(CheckInOutcome.Failed(CheckInError.AUTHENTICATION_REQUIRED), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        assertEquals(VolunteerOutcome.Failed(VolunteerError.AUTHENTICATION_REQUIRED), e.volunteers.grant(FakeFixtures.OPEN, "alex"))
    }
    @Test fun invalidWrongEventCancelledUnknownAndFinishedDoNotAdmit() = runBlocking {
        val time = Time(); val e = backend(clock = time); val t = ticket(e); e.scenarios.signInAs(FakeFixtures.HOST)
        assertEquals(CheckInOutcome.Invalid(InvalidTicketReason.WRONG_EVENT), e.checkIns.checkIn(FakeFixtures.OPEN, qr(t)))
        assertEquals(CheckInOutcome.Invalid(InvalidTicketReason.UNKNOWN), e.checkIns.checkIn(FakeFixtures.MIXED, TicketCredential.Qr(QrToken("unknown"))))
        assertEquals(CheckInOutcome.Failed(CheckInError.INVALID_INPUT), e.checkIns.checkIn(FakeFixtures.MIXED, TicketCredential.Manual(ManualCode(t.snapshot.displayNumber))))
        time.now = FakeFixtures.NOW.plusSeconds(200000)
        assertEquals(CheckInOutcome.Invalid(InvalidTicketReason.EVENT_INACTIVE), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        time.now = FakeFixtures.NOW
        e.scenarios.setTicketStatus(t.snapshot.ticketId, TicketStatus.CANCELLED)
        assertEquals(CheckInOutcome.Invalid(InvalidTicketReason.CANCELLED), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        e.scenarios.signInAs(FakeFixtures.ALEX); assertEquals(TicketStatus.CANCELLED, own(e).status)
    }
    @Test fun closeAndStartDoNotCancelAdmissionButUsedRepeatKeepsOriginalChecker() = runBlocking {
        val time = Time(); val e = backend(clock = time); val t = ticket(e); e.scenarios.signInAs(FakeFixtures.HOST)
        e.events.closeRegistration(FakeFixtures.MIXED, 1)
        val event = (e.events.observePublicEvent(FakeFixtures.MIXED).first() as PublicEventResult.Found).event
        time.now = event.startsAt
        val first = e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)) as CheckInOutcome.Admitted
        e.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(CheckInOutcome.AlreadyUsed(first.record), e.checkIns.checkIn(FakeFixtures.MIXED, manual(t)))
    }
    @Test fun legacyUsedHasNoInventedCheckerAndCannotBeReadByUnauthorizedActor() = runBlocking {
        val e = backend(); val t = ticket(e); e.scenarios.setTicketStatus(t.snapshot.ticketId, TicketStatus.USED)
        assertEquals(CheckInOutcome.AlreadyUsed(null), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        e.scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, false))
        assertEquals(CheckInOutcome.Failed(CheckInError.FORBIDDEN), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
    }
    @Test fun storageAndTransportFailuresCannotPublishAdmittedOrGrant() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e); e.scenarios.signInAs(FakeFixtures.HOST)
        val before = storage.bytes
        storage.fail = true
        assertEquals(CheckInOutcome.Failed(CheckInError.STORAGE_UNAVAILABLE), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        assertEquals(VolunteerOutcome.Failed(VolunteerError.STORAGE_UNAVAILABLE), e.volunteers.grant(FakeFixtures.OPEN, "alex"))
        assertArrayEquals(before, storage.bytes)
        storage.fail = false
        for (error in listOf(CheckInError.NETWORK, CheckInError.TIMEOUT)) {
            e.scenarios.failNextCheckIn(error)
            assertEquals(CheckInOutcome.Failed(error), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        }
        e.scenarios.signInAs(FakeFixtures.ALEX); assertEquals(TicketStatus.ACTIVE, own(e).status)
        assertFalse((e.events.observeMyEvents().first() as MyEventsResult.Ready).volunteering.any { it.id == FakeFixtures.OPEN })
    }
    @Test fun concurrentCallsIssueOneRecordAndNoExtraAttendance() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e)
        val results = (1..8).map { async(Dispatchers.Default) { e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)) } }.awaitAll()
        assertEquals(1, results.count { it is CheckInOutcome.Admitted }); assertEquals(7, results.count { it is CheckInOutcome.AlreadyUsed })
        val records = ProductSnapshotCodec.restore(storage.bytes!!, seed()).checkIns
        assertEquals(1, records.size)
        assertTrue(results.filterIsInstance<CheckInOutcome.AlreadyUsed>().all { it.first == records.single() })
    }
    @Test fun expiryAndUnverifiedAccountCannotScanOrIssueGrants() = runBlocking {
        val authTime = Time(); val e = backend(authClock = authTime); val t = ticket(e)
        authTime.now = authTime.now.plusSeconds(1800); e.checkIns.refreshAccess(FakeFixtures.MIXED)
        assertEquals(ScannerAccess.Denied(CheckInError.AUTHENTICATION_REQUIRED), e.checkIns.observeAccess(FakeFixtures.MIXED).first())
        assertEquals(CheckInOutcome.Failed(CheckInError.AUTHENTICATION_REQUIRED), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        e.scenarios.putAccount(Account(FakeFixtures.ALEX, "alex@example.com", false)); e.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(CheckInOutcome.Failed(CheckInError.AUTHENTICATION_REQUIRED), e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
    }
    @Test fun v3MigrationPreservesAllFieldsAndCreatesStableManualCodesWithoutRotatingQr() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e)
        e.events.saveDraft(DraftId("migration"), null, EventFields(title = "Неполный"))
        e.auth.signUp("New", "new@example.com", AuthSecret("NewPass123!"))
        val before = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        storage.bytes = LegacyV3Fixture.encode(before)
        val next = backend(storage); next.scenarios.signInAs(FakeFixtures.ALEX)
        val restored = own(next)
        assertEquals(t.snapshot.qrPayload, restored.snapshot.qrPayload); assertEquals(t.snapshot.manualCode, restored.snapshot.manualCode)
        assertTrue(next.checkIns.checkIn(FakeFixtures.MIXED, manual(t)) is CheckInOutcome.Admitted)
        val after = ProductSnapshotCodec.restore(storage.bytes!!, seed())
        assertEquals(5, java.io.DataInputStream(storage.bytes!!.inputStream()).readInt())
        assertEquals(before.accounts, after.accounts); assertEquals(before.auth.passwords, after.auth.passwords)
        assertEquals(before.auth.challenges, after.auth.challenges); assertEquals(before.events, after.events)
        assertEquals(before.drafts, after.drafts); assertEquals(before.registrations, after.registrations); assertEquals(before.grants, after.grants)
    }
    @Test fun authEditorAndRegistrationWritesPreserveCheckInsAndGrantAcrossRestart() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e); e.scenarios.signInAs(FakeFixtures.HOST)
        e.volunteers.grant(FakeFixtures.OPEN, "alex")
        val first = e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)) as CheckInOutcome.Admitted
        e.auth.signUp("New", "new@example.com", AuthSecret("NewPass123!"))
        e.scenarios.signInAs(FakeFixtures.HOST)
        e.events.saveDraft(DraftId("other"), null, EventFields())
        e.registrations.submitGuest(GuestRegistrationCommand(FakeFixtures.OPEN, 1, "Guest", "guest@example.com", consentAccepted = true))
        val next = backend(storage); next.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(CheckInOutcome.AlreadyUsed(first.record), next.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
        assertTrue((next.events.observeMyEvents().first() as MyEventsResult.Ready).volunteering.any { it.id == FakeFixtures.OPEN })
    }
    @Test fun corruptV4NeverResetsOrOverwritesTheSnapshot() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e)
        e.checkIns.checkIn(FakeFixtures.MIXED, qr(t)); val valid = storage.bytes!!.copyOf()
        for (invalid in listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(1))) {
            storage.bytes = invalid
            val broken = backend(storage)
            assertEquals(CheckInOutcome.Failed(CheckInError.STORAGE_UNAVAILABLE), broken.checkIns.checkIn(FakeFixtures.MIXED, qr(t)))
            assertArrayEquals(invalid, storage.bytes)
        }
    }
    @Test fun queuedCheckInRechecksRevocationAndSessionBeforeMutation() = runBlocking {
        for (changeSession in listOf(false, true)) {
            val storage = Storage(); val state = kotlinx.coroutines.flow.MutableStateFlow(seed())
            val mutex = kotlinx.coroutines.sync.Mutex()
            val auth = FakeAuthRepository(state, mutex, fixed, storage, FakeAuthPolicy(passwordIterations = 1000), RandomAuthSecrets()) {}
            auth.initialize()
            state.value = state.value.copy(auth = state.value.auth.copy(session = Session("s", state.value.accounts.getValue(FakeFixtures.ALEX), FakeFixtures.NOW.plusSeconds(1800))))
            val repo = FakeCheckInOperations(state, mutex, fixed, fixed, storage, auth).checkIns
            val t = state.value.tickets.single { it.snapshot.eventId == FakeFixtures.MIXED }
            mutex.lock()
            try {
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { repo.checkIn(FakeFixtures.MIXED, qr(t)) }
                state.value = if (changeSession) state.value.copy(auth = state.value.auth.copy(session = null, generation = 1))
                    else state.value.copy(grants = emptyList())
                mutex.unlock()
                assertEquals(CheckInOutcome.Failed(if (changeSession) CheckInError.SESSION_CHANGED else CheckInError.FORBIDDEN), waiting.await())
                assertTrue(state.value.checkIns.isEmpty()); assertEquals(TicketStatus.ACTIVE, state.value.tickets.single { it.snapshot.ticketId == t.snapshot.ticketId }.status)
            } finally { if (mutex.isLocked) mutex.unlock() }
        }
    }
    @Test fun queuedCancellationFreesSingleCallerBeforeForeignWriteIsReleased() = runBlocking {
        val state = kotlinx.coroutines.flow.MutableStateFlow(seed()); val mutex = kotlinx.coroutines.sync.Mutex(); val storage = Storage()
        val auth = FakeAuthRepository(state, mutex, fixed, storage, FakeAuthPolicy(passwordIterations = 1000), RandomAuthSecrets()) {}
        auth.initialize()
        state.value = state.value.copy(auth = state.value.auth.copy(session = Session("s", state.value.accounts.getValue(FakeFixtures.HOST), FakeFixtures.NOW.plusSeconds(1800))))
        val repo = FakeCheckInOperations(state, mutex, fixed, fixed, storage, auth).checkIns
        val t = state.value.tickets.first()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor(); val caller = executor.asCoroutineDispatcher()
        mutex.lock()
        try {
            val waiting = async(caller) { repo.checkIn(t.snapshot.eventId, qr(t)) }
            assertEquals("responsive", withTimeout(2000) { async(caller) { "responsive" }.await() })
            withTimeout(2000) { waiting.cancelAndJoin() }
            assertTrue(mutex.isLocked); assertTrue(state.value.checkIns.isEmpty()); assertNull(storage.bytes)
        } finally { mutex.unlock(); caller.close(); executor.shutdownNow() }
    }
    @Test fun migratedLegacyUsedDoesNotFabricateHistoricalAdmission() = runBlocking {
        val storage = Storage(); val e = backend(storage); val t = ticket(e)
        e.scenarios.setTicketStatus(t.snapshot.ticketId, TicketStatus.USED)
        storage.bytes = LegacyV3Fixture.encode(ProductSnapshotCodec.restore(storage.bytes!!, seed()))
        val next = backend(storage); next.scenarios.signInAs(FakeFixtures.ALEX)
        assertEquals(t.snapshot.qrPayload, own(next).snapshot.qrPayload)
        assertEquals(CheckInOutcome.AlreadyUsed(null), next.checkIns.checkIn(FakeFixtures.MIXED, manual(t)))
    }
}
