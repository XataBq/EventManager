package ru.eventpass.data.remote

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.registration.*

/** Opt-in real backend integration. Every account is created here; never uses personal fixtures. */
class StagingOperationsSmokeTest {
    private class Actor(val api:AuthHttp,val email:String) {
        var deleted=false
        var secret:AuthSecret?=null
        val password=AuthSecret(UUID.randomUUID().toString())
        val auth=RemoteAuthRepository(api,object:SessionTokenStore {
            override suspend fun read()=secret
            override suspend fun write(token:AuthSecret) {secret=token}
            override suspend fun clear(){secret=null}
        })
        private val record=Path.of("../../.gradle/m09-evidence/staging-fixtures",email.substringBefore('@')+".private.json")
        private fun record() {Files.createDirectories(record.parent);Files.writeString(record,buildJsonObject {put("email",email);put("password",password.value);secret?.let {put("token",it.value)}}.toString())}
        suspend fun create() {
            record()
            repeat(3) {
                val result=auth.signUp("M09 synthetic 李",email,password,true)
                record()
                if(result is AuthResult.Success) return
                val error=(result as AuthResult.Failed).error
                if(error is AuthError.Cooldown && java.time.Duration.between(Instant.now(),error.retryAt).seconds<=120) {
                    delay(java.time.Duration.between(Instant.now(),error.retryAt).toMillis().coerceAtLeast(1)+250)
                } else throw AssertionError("Synthetic signup failed: $error")
            }
            error("Synthetic signup cooldown did not clear")
        }
        suspend fun cleanup() {
            if(deleted) return
            if(secret==null) auth.signIn(email,password)
            secret?.let {api.request("me",token=it,method="DELETE",expectedStatus=204);Files.deleteIfExists(record)}
        }
        suspend fun deleteConfirmed() {
            assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Deleted)
            deleted=true;Files.deleteIfExists(record)
        }
    }
    @Test fun realPagesConcurrentScanGrantRevokeProfileAndServerFiles()=runBlocking<Unit> {
        assumeTrue(System.getenv("EVENTPASS_STAGING_SMOKE")=="1")
        val api=AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
        val actors=mutableListOf<Actor>();val prefix="android.m09.${UUID.randomUUID()}"
        suspend fun actor(index:Int)=Actor(api,"$prefix.$index@example.com").also {actors+=it;it.create()}
        try {
            val owner=actor(0)
            val body=buildJsonObject {
                put("title","M09 synthetic ${UUID.randomUUID()}");put("description","Own temporary fixtures only")
                put("location","Staging");put("startsAt",Instant.now().minusSeconds(3600).toString());put("capacity",30);put("publish",true)
                put("questions",buildJsonArray {add(buildJsonObject {put("id","historical-bool");put("type","checkbox");put("label","Original boolean label");put("required",false)})})
            }.toString()
            val event=api.json.parseToJsonElement(api.request("events",body,owner.secret)).jsonObject
            val id=EventId(event.getValue("id").jsonPrimitive.content)
            val ticketIds=mutableListOf<TicketId>()
            for(index in 1..21) {
                val participant=actor(index)
                val registration=RemoteRegistrations(api,participant.auth).submitAuthenticated(AuthenticatedRegistrationCommand(id,null,"ignored",answers=mapOf("historical-bool" to Answer.Checkbox(false)),consentAccepted=true))
                assertTrue("Synthetic registration $index",registration is AuthenticatedRegistrationOutcome.Registered)
                ticketIds+=(registration as AuthenticatedRegistrationOutcome.Registered).ticketId
            }
            val scanner=RemoteCheckInRepository(api,owner.auth);val volunteers=RemoteVolunteersRepository(api,owner.auth)
            val organizer=RemoteOrganizerRepository(api,owner.auth,M09ProtocolFixture.unavailable)
            val attendance=RemoteAttendanceRepository(api,owner.auth,organizer,volunteers,RemoteTickets(api,owner.auth),scanner)
            val dashboard=attendance.refreshDashboard(id) as DashboardResult.Ready
            assertEquals(21,dashboard.dashboard.summary.registered);assertEquals(20,dashboard.firstPage!!.rows.size)
            val second=attendance.attendees(id,"",dashboard.firstPage!!.next) as AttendeesResult.Page
            assertEquals(1,second.rows.size)
            assertEquals(21,(dashboard.firstPage!!.rows+second.rows).map {it.ticketId}.distinct().size)
            val checker=actors[1]
            val grant=volunteers.grant(id,checker.email) as VolunteerOutcome.Granted
            val checkerScanner=RemoteCheckInRepository(api,checker.auth)
            scanner.refreshAccess(id);checkerScanner.refreshAccess(id)
            assertTrue(checkerScanner.observeAccess(id).first() is ScannerAccess.Allowed)
            val ticket=(RemoteTickets(api,checker.auth).observeOwnTicket(ticketIds.first()).first() as OwnTicketResult.Found).ticket
            val credential=TicketCredential.Manual(ticket.snapshot.manualCode)
            val receipts=awaitAll(async(Dispatchers.Default){scanner.checkIn(id,ScanCommand(credential))},async(Dispatchers.Default){checkerScanner.checkIn(id,ScanCommand(credential))})
            assertEquals(setOf(ScanDecision.ALLOWED,ScanDecision.ALREADY_USED),receipts.map {(it as CheckInOutcome.Confirmed).decision}.toSet())
            assertTrue(attendance.cancelTicket(id,ticketIds.first()) is OwnerCancelResult.Failed)
            assertTrue(attendance.cancelTicket(id,ticketIds[1]) is OwnerCancelResult.Cancelled)
            assertTrue(volunteers.revoke(id,grant.volunteer.grant.userId) is VolunteerOutcome.Revoked)
            checkerScanner.refreshAccess(id);assertTrue(checkerScanner.observeAccess(id).first() is ScannerAccess.Denied)
            val after=attendance.refreshDashboard(id) as DashboardResult.Ready
            assertEquals(20,after.dashboard.summary.registered);assertEquals(1,after.dashboard.summary.checkedIn)
            val profile=owner.auth.profileRepository.observeProfile().first() as ProfileResult.Ready
            val session=(owner.auth.currentSession().state as SessionState.Authenticated).session
            assertTrue(owner.auth.profileRepository.saveProfile(profile.profile.fields.copy(email="$prefix.changed@example.com",emailNotifications=false)) is ProfileResult.Ready)
            assertEquals(session.id,(owner.auth.currentSession().state as SessionState.Authenticated).session.id)
            assertEquals(20,(attendance.refreshDashboard(id) as DashboardResult.Ready).dashboard.summary.registered)
            val documents=RemoteTicketDocuments(api,owner.auth)
            val xlsx=documents.prepare(DocumentRequest.AttendanceXlsx(id)) as DocumentResult.Ready
            val output=ByteArrayOutputStream();(xlsx.snapshot as DocumentSnapshot.Remote).source.writeTo(output)
            val bytes=output.toByteArray();val text=buildString {ZipInputStream(bytes.inputStream()).use {zip->while(true) {val entry=zip.nextEntry ?: break;if(entry.name.endsWith(".xml")) append(zip.readBytes().toString(Charsets.UTF_8))}}}
            // Cancelled attendee must remain in the complete export, irrespective of a filtered UI page.
            assertTrue(text.contains(actors[2].email));assertTrue(text.contains("Original boolean label"))
            val files=Path.of("../../.gradle/m09-evidence/files");Files.createDirectories(files);Files.write(files.resolve("staging-attendees.xlsx"),bytes)
            val pdf=RemoteTicketDocuments(api,checker.auth).prepare(DocumentRequest.TicketPdf(ticketIds.first())) as DocumentResult.Ready
            val pdfOutput=ByteArrayOutputStream();(pdf.snapshot as DocumentSnapshot.Remote).source.writeTo(pdfOutput)
            assertTrue(pdfOutput.toByteArray().toString(Charsets.ISO_8859_1).startsWith("%PDF-"));Files.write(files.resolve("staging-ticket.pdf"),pdfOutput.toByteArray())
            // The owner's account deletion removes its events and all participants' tickets,
            // without deleting the other accounts. Keep the checker alive to verify that boundary.
            owner.deleteConfirmed()
            checker.auth.refreshSession();assertTrue(checker.auth.currentSession().state is SessionState.Authenticated)
            val afterCascade=RemoteTickets(api,checker.auth)
            for(scope in TicketScope.entries) {
                val empty=afterCascade.observeMyTickets(scope).first() as MyTicketsResult.Ready
                assertTrue(empty.tickets.isEmpty())
            }
            assertFalse(afterCascade.observeOwnTicket(ticketIds.first()).first() is OwnTicketResult.Found)
        } finally {
            val failures=mutableListOf<Exception>()
            for(actor in actors.asReversed()) try {actor.cleanup()} catch(ex:Exception){failures+=ex}
            check(failures.isEmpty()) {"Synthetic fixture cleanup failed for ${failures.size} accounts"}
        }
    }
}
