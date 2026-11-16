package ru.eventpass.data.remote

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.documents.*

/** Only a synthetic owner, own event and own ticket. All are removed in finally. */
class StagingTicketsSmokeTest {
    @Test fun authenticatedLiveRegistrationListCardPdfCancelAndCleanup() = runBlocking {
        assumeTrue(System.getenv("EVENTPASS_STAGING_SMOKE") == "1")
        val api = AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
        var secret: AuthSecret? = null
        val store = object : SessionTokenStore {
            override suspend fun read() = secret
            override suspend fun write(token: AuthSecret) { secret = token }
            override suspend fun clear() { secret = null }
        }
        val auth = RemoteAuthRepository(api,store)
        val email = "android.m03.${UUID.randomUUID()}@example.com"
        val password = AuthSecret(UUID.randomUUID().toString())
        try {
            assertTrue(auth.signUp("Android M03 audit",email,password,true) is AuthResult.Success)
            val body = buildJsonObject {
                put("title","Android M03 ${UUID.randomUUID()}"); put("description","Synthetic integration check")
                put("location","Staging"); put("startsAt",Instant.now().minusSeconds(3600).toString())
                put("capacity",3); put("publish",true); put("questions",buildJsonArray {})
            }.toString()
            val event = api.json.parseToJsonElement(api.request("events",body,secret)).jsonObject
            val result = RemoteRegistrations(api,auth).submitAuthenticated(AuthenticatedRegistrationCommand(
                EventId(event.getValue("id").jsonPrimitive.content),null,"Ignored client contact",consentAccepted=true))
            assertTrue(result is AuthenticatedRegistrationOutcome.Registered)
            val id = (result as AuthenticatedRegistrationOutcome.Registered).ticketId
            val tickets = RemoteTickets(api,auth)
            val list = tickets.observeMyTickets(TicketScope.UPCOMING).first() as MyTicketsResult.Ready
            assertTrue(list.tickets.any { it.snapshot.ticketId == id })
            val own = tickets.observeOwnTicket(id).first() as OwnTicketResult.Found
            assertNull(own.event); assertEquals("Android M03 audit",own.ticket.snapshot.attendeeName)
            assertEquals(TicketStatus.ACTIVE,own.ticket.status)
            val document = RemoteTicketDocuments(api,auth).prepare(DocumentRequest.TicketPdf(id)) as DocumentResult.Ready
            val output = java.io.ByteArrayOutputStream()
            (document.snapshot as DocumentSnapshot.Remote).source.writeTo(output)
            assertTrue(output.toByteArray().take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-")
            assertTrue(tickets.cancelOwnTicket(id) is CancelTicketOutcome.Cancelled)
            val past = tickets.observeMyTickets(TicketScope.PAST).first() as MyTicketsResult.Ready
            assertEquals(TicketStatus.CANCELLED,past.tickets.single { it.snapshot.ticketId == id }.status)
        } finally {
            if(secret == null) auth.signIn(email,password)
            secret?.let { api.request("me",token=it,method="DELETE") }
            auth.signOut()
        }
    }
}
