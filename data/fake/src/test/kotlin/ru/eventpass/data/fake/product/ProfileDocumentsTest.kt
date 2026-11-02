package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.checkin.*

class ProfileDocumentsTest {
    private class Storage : FakeAuthStorage {
        var bytes: ByteArray?=null; var fail=false
        override fun read()=bytes?.copyOf()
        override fun write(snapshot: ByteArray) { if(fail) throw IOException("Fixture"); bytes=snapshot.copyOf() }
    }
    private class Time : Clock() {
        var now=FakeFixtures.NOW
        override fun instant()=now
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock=Clock.fixed(now,zone)
    }
    private fun env(storage: FakeAuthStorage=VolatileAuthStorage, time: Clock=Clock.fixed(FakeFixtures.NOW,ZoneOffset.UTC),
        before: suspend(String)->Unit = {}) = FakeEventPassEnvironment(authStorage=storage,authClock=time,
        authPolicy=FakeAuthPolicy(passwordIterations=1000),beforeProfileDocumentOperation=before)
    private fun seed()=FakeProductState(FakeFixtures.accounts().associateBy {it.id},FakeFixtures.events().associateBy {it.id},
        FakeFixtures.registrations(),FakeFixtures.tickets(FakeFixtures.events()),FakeFixtures.grants())
    private suspend fun fields(e: FakeEventPassEnvironment)=(e.profiles.observeProfile().first() as ProfileResult.Ready).profile.fields
    private suspend fun own(e: FakeEventPassEnvironment)=(e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.first { it.snapshot.eventId==FakeFixtures.MIXED }
    private val changed=ProfileFields("李明 Александра","8 (999) 123-45-67","Московский политех",false)

    @Test fun profilePersistsWithoutChangingIdentityOrOldRegistrationAndFeedsNewForms() = runTest {
        val storage=Storage(); val e=env(storage); e.scenarios.signInAs(FakeFixtures.ALEX)
        val session=e.auth.currentSession(); val ticket=own(e); val old=e.scenarios.registrationFor(ticket.snapshot.ticketId)
        val saved=e.profiles.saveProfile(changed) as ProfileResult.Ready
        assertEquals("+79991234567",saved.profile.fields.phone); assertEquals(session,e.auth.currentSession())
        assertEquals(ticket,own(e)); assertEquals(old,e.scenarios.registrationFor(ticket.snapshot.ticketId))
        val contact=(e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found).contact!!
        assertEquals(changed.name,contact.name); assertEquals("+79991234567",contact.phone)
        val result=e.registrations.submitAuthenticated(AuthenticatedRegistrationCommand(FakeFixtures.OPEN,1,contact.name,contact.phone,consentAccepted=true)) as AuthenticatedRegistrationOutcome.Registered
        val new=e.scenarios.registrationFor(result.ticketId)!!; assertEquals(contact.phone,new.phone); assertEquals(changed.name,new.attendeeName); assertTrue(new.answers.isEmpty())
        val restarted=env(storage)
        assertTrue(restarted.auth.signIn("alex@example.com",AuthSecret("DemoPass123!")) is AuthResult.Success)
        assertEquals(saved.profile.fields,fields(restarted)); assertEquals(old,restarted.scenarios.registrationFor(ticket.snapshot.ticketId))
    }
    @Test fun validationAndStorageFailureDoNotPublishFalseSave() = runTest {
        val storage=Storage(); val e=env(storage); e.scenarios.signInAs(FakeFixtures.ALEX); val before=fields(e)
        for (value in listOf(changed.copy(name=" "),changed.copy(phone="+12025550123"),changed.copy(organization="x".repeat(201))))
            assertEquals(ProfileResult.Failed(ProfileError.INVALID_INPUT),e.profiles.saveProfile(value))
        storage.fail=true; assertEquals(ProfileResult.Failed(ProfileError.STORAGE_UNAVAILABLE),e.profiles.saveProfile(changed)); assertEquals(before,fields(e))
        storage.fail=false; assertTrue(e.profiles.saveProfile(changed.copy(name="Ж",phone=null,organization=null)) is ProfileResult.Ready)
        e.auth.signOut(); assertEquals(ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED),e.profiles.saveProfile(changed))
    }
    @Test fun queuedProfileAndDocumentCommandsRespectSessionAndCancellation() = runTest {
        var enabled=false; var gate=CompletableDeferred<Unit>(); var entered=CompletableDeferred<Unit>()
        val e=env(before={ if(enabled) {entered.complete(Unit);gate.await()} }); e.scenarios.signInAs(FakeFixtures.ALEX)
        enabled=true
        val save=async { e.profiles.saveProfile(changed) }; entered.await(); e.auth.signOut(); gate.complete(Unit)
        assertEquals(ProfileResult.Failed(ProfileError.SESSION_CHANGED),save.await())
        e.scenarios.signInAs(FakeFixtures.ALEX); gate=CompletableDeferred(); entered=CompletableDeferred()
        val cancelled=async { e.profiles.saveProfile(changed) }; entered.await(); cancelled.cancelAndJoin(); assertEquals("",fields(e).name)
        gate=CompletableDeferred(); entered=CompletableDeferred()
        val doc=async { e.documents.prepare(DocumentRequest.AttendanceXlsx(FakeFixtures.OWNED)) }; entered.await()
        e.scenarios.signInAs(FakeFixtures.HOST); gate.complete(Unit)
        assertEquals(DocumentResult.Failed(DocumentError.SESSION_CHANGED),doc.await())
    }
    @Test fun migrationV1ThroughV4PreservesNamesAndAllExistingDataThenAllWritersKeepProfile() = runTest {
        val storage=Storage(); val e=env(storage); e.scenarios.signInAs(FakeFixtures.HOST)
        e.auth.signUp("Signup имя","new@example.com",AuthSecret("NewPass123!")); e.scenarios.signInAs(FakeFixtures.HOST)
        e.events.saveDraft(DraftId("retained"),null,EventFields(title="Черновик"))
        e.checkIns.checkIn(FakeFixtures.MIXED,TicketCredential.Qr(QrToken("demo-token-alex-mixed")))
        val before=ProductSnapshotCodec.restore(storage.bytes!!,seed())
        val versions=listOf(AuthSnapshotCodec.encode(before.accounts,before.auth),LegacyV2Fixture.encode(before),LegacyV3Fixture.encode(before),LegacyV4Fixture.encode(before))
        for((index,bytes) in versions.withIndex()) {
            storage.bytes=bytes; val migrated=env(storage); migrated.scenarios.signInAs(FakeFixtures.HOST)
            val expected=ProductSnapshotCodec.restore(bytes,seed()); assertEquals(ProfileFields(),fields(migrated))
            assertTrue(migrated.profiles.saveProfile(changed) is ProfileResult.Ready)
            migrated.auth.signUp("Other","writer@example.com",AuthSecret("Pass12345!")); migrated.scenarios.signInAs(FakeFixtures.HOST)
            migrated.events.saveDraft(DraftId("writer"),null,EventFields())
            migrated.registrations.submitGuest(GuestRegistrationCommand(FakeFixtures.OPEN,1,"Guest","guest@example.com",consentAccepted=true))
            migrated.volunteers.grant(FakeFixtures.OPEN,"alex")
            if(index<3) migrated.checkIns.checkIn(FakeFixtures.MIXED,TicketCredential.Qr(QrToken("demo-token-alex-mixed")))
            migrated.attendance.cancelRegistration(FakeFixtures.ATTENDING,"seed-registration-alex-attending")
            migrated.scenarios.signInAs(FakeFixtures.ALEX)
            val added=migrated.registrations.submitAuthenticated(AuthenticatedRegistrationCommand(FakeFixtures.OWNED,1,"Owner",consentAccepted=true)) as AuthenticatedRegistrationOutcome.Registered
            assertTrue(migrated.tickets.cancelOwnTicket(added.ticketId) is CancelTicketOutcome.Cancelled)
            val restored=ProductSnapshotCodec.restore(storage.bytes!!,seed())
            assertEquals(5,java.io.DataInputStream(storage.bytes!!.inputStream()).readInt())
            assertEquals(ProfileDetails("+79991234567",changed.organization,false),restored.profileDetails[FakeFixtures.HOST])
            assertEquals(changed.name,restored.auth.names[FakeFixtures.HOST]); assertTrue(restored.auth.names.values.contains("Signup имя"))
            assertTrue(restored.auth.passwords.entries.containsAll(expected.auth.passwords.entries))
            assertTrue(restored.auth.challenges.entries.containsAll(expected.auth.challenges.entries))
            assertEquals(expected.events,restored.events); assertTrue(restored.drafts.entries.containsAll(expected.drafts.entries))
            expected.tickets.forEach { old -> assertEquals(old.snapshot,restored.tickets.single {it.snapshot.ticketId==old.snapshot.ticketId}.snapshot) }
            assertEquals(expected.registrations,restored.registrations.take(expected.registrations.size)); assertTrue(restored.checkIns.containsAll(expected.checkIns))
            val restarted=env(storage); restarted.scenarios.signInAs(FakeFixtures.HOST); assertEquals(ProfilePolicy.normalized(changed),fields(restarted))
        }
    }
    @Test fun corruptAndUnsupportedProfileSnapshotNeverOverwriteOrReset() = runTest {
        val storage=Storage(); val e=env(storage); e.scenarios.signInAs(FakeFixtures.ALEX); e.profiles.saveProfile(changed)
        val valid=storage.bytes!!
        for(bytes in listOf(valid.copyOf(valid.size-1),valid+byteArrayOf(1),valid.copyOf().also {it[3]=6})) {
            storage.bytes=bytes; val broken=env(storage)
            assertEquals(ProfileResult.Failed(ProfileError.STORAGE_UNAVAILABLE),broken.profiles.saveProfile(changed)); assertArrayEquals(bytes,storage.bytes)
        }
    }
    @Test fun pdfRequiresOwnCurrentActiveTicketAndValidationRejectsStatusLogoutAndExpiredSession() = runTest {
        val time=Time(); val e=env(time=time); e.scenarios.signInAs(FakeFixtures.ALEX); val t=own(e); val request=DocumentRequest.TicketPdf(t.snapshot.ticketId)
        val pdf=(e.documents.prepare(request) as DocumentResult.Ready).snapshot as DocumentSnapshot.Ticket
        assertEquals(t.snapshot,pdf.ticket); assertNull(e.documents.validate(pdf)); assertEquals(t,own(e))
        e.scenarios.setTicketStatus(t.snapshot.ticketId,TicketStatus.USED); assertEquals(DocumentError.NOT_CURRENT,e.documents.validate(pdf))
        assertEquals(DocumentResult.Failed(DocumentError.NOT_CURRENT),e.documents.prepare(request))
        e.scenarios.signInAs(FakeFixtures.HOST); assertEquals(DocumentResult.Failed(DocumentError.FORBIDDEN),e.documents.prepare(request))
        assertEquals(DocumentError.SESSION_CHANGED,e.documents.validate(pdf))
        e.auth.signOut(); assertEquals(DocumentResult.Failed(DocumentError.AUTHENTICATION_REQUIRED),e.documents.prepare(request))
        e.scenarios.signInAs(FakeFixtures.ALEX); time.now=time.now.plusSeconds(1801)
        assertEquals(DocumentResult.Failed(DocumentError.AUTHENTICATION_REQUIRED),e.documents.prepare(request))
    }
    @Test fun exportIsOneFullOwnerSnapshotWithAnswersAllStatusesAndNoSearchOrFakeHistory() = runTest {
        val e=env(); e.scenarios.signInAs(FakeFixtures.ALEX)
        e.scenarios.putEvent(FakeFixtures.events().single {it.id==FakeFixtures.OWNED}.copy(capacity=null))
        repeat(25) { n -> e.registrations.submitGuest(GuestRegistrationCommand(FakeFixtures.OWNED,1,"=$n","g$n@example.com",
            answers=mapOf("organization" to Answer.Text("=SUM(1,2)"),"workshop" to Answer.Checkbox(n%2==0),"format" to Answer.SingleChoice("listener")),consentAccepted=true)) }
        val request=DocumentRequest.AttendanceXlsx(FakeFixtures.OWNED)
        val snapshot=(e.documents.prepare(request) as DocumentResult.Ready).snapshot as DocumentSnapshot.Attendance
        assertEquals(20,(e.attendance.attendees(FakeFixtures.OWNED,"") as ru.eventpass.core.domain.attendance.AttendeesResult.Page).rows.size)
        assertEquals(25,snapshot.rows.size); assertEquals(3,snapshot.rows.first().registration.answers.size)
        assertTrue(snapshot.rows.all {it.checkedAt==null && it.checkerId==null}); assertNull(e.documents.validate(snapshot))
        e.scenarios.signInAs(FakeFixtures.HOST); assertEquals(DocumentResult.Failed(DocumentError.FORBIDDEN),e.documents.prepare(request))
        val mixed=(e.documents.prepare(DocumentRequest.AttendanceXlsx(FakeFixtures.MIXED)) as DocumentResult.Ready).snapshot as DocumentSnapshot.Attendance
        e.attendance.cancelRegistration(FakeFixtures.MIXED,mixed.rows.single().registration.id)
        val cancelled=(e.documents.prepare(mixed.request) as DocumentResult.Ready).snapshot as DocumentSnapshot.Attendance
        assertEquals(TicketStatus.CANCELLED,cancelled.rows.single().status); assertEquals(TicketStatus.ACTIVE,mixed.rows.single().status)
    }
    @Test fun resendUsesRegisteredRecipientSharedOutboxAuthClockCooldownAndNeverMutatesTicket() = runTest {
        val time=Time(); val e=env(time=time); e.scenarios.signInAs(FakeFixtures.ALEX); val t=own(e)
        e.profiles.saveProfile(changed); e.scenarios.failNextDocument(DocumentError.NETWORK)
        assertEquals(ResendResult.Failed(DocumentError.NETWORK),e.documents.resendTicket(t.snapshot.ticketId)); assertTrue(e.scenarios.outbox().isEmpty())
        val first=e.documents.resendTicket(t.snapshot.ticketId) as ResendResult.Queued
        assertEquals(listOf(FakeTicketEmail("alex@example.com",t.snapshot.ticketId)),e.scenarios.outbox())
        assertEquals(ResendResult.Cooldown(first.retryAt),e.documents.resendTicket(t.snapshot.ticketId)); assertEquals(t,own(e))
        time.now=first.retryAt; assertTrue(e.documents.resendTicket(t.snapshot.ticketId) is ResendResult.Queued); assertEquals(2,e.scenarios.outbox().size)
        e.scenarios.signInAs(FakeFixtures.HOST); assertEquals(ResendResult.Failed(DocumentError.FORBIDDEN),e.documents.resendTicket(t.snapshot.ticketId))
    }
}
