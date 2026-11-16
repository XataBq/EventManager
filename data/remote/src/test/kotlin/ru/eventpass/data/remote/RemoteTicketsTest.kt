package ru.eventpass.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.documents.*

class RemoteTicketsTest {
    private class Store : SessionTokenStore {
        var token: AuthSecret? = null
        override suspend fun read() = token
        override suspend fun write(token: AuthSecret) { this.token = token }
        override suspend fun clear() { token = null }
    }
    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    private val ticket = """{"id":"t1","code":"EVT-123456","qrToken":"opaque-qr","status":"active","holderName":"Alex","holderEmail":"alex@example.com","category":"General","zone":"Main","createdAt":"2026-11-16T09:00:00Z","checkedInAt":null,"event":{"id":"e1","slug":"конференция","title":"Conference","status":"live","startsAt":"2026-11-16T09:00:00Z","endsAt":null,"effectiveEndsAt":"2026-11-16T13:00:00Z","finishedAt":null,"location":"Hall","capacity":3,"registeredCount":1,"checkedInCount":0,"category":"General"}}"""
    private val user = """{"id":"actor","publicId":"123456","fullName":"Alex","email":"alex@example.com","phone":null,"organization":null,"emailVerified":false,"notificationsEnabled":true}"""
    private val command get() = GuestRegistrationCommand(EventId("e1"), null, "Alex", "alex@example.com",
        answers = mapOf("bool" to Answer.Checkbox(false), "many" to Answer.MultipleChoice(listOf("a","b"))), consentAccepted = true)

    @Test fun guestAccountCapabilityIsNotAnAuthSessionAndTypedBodyUsesKey() = runTest {
        MockWebServer().use { server -> server.start()
            server.enqueue(json("""{"ticket":$ticket,"accountCreated":true,"accessToken":"guest-secret"}""", 201))
            val api = AuthHttp(server.url("/api/").toString()); val store = Store(); val auth = RemoteAuthRepository(api, store)
            val c = command; val issued = RemoteRegistrations(api,auth).submitGuest(c) as GuestRegistrationOutcome.Issued
            assertTrue(issued.accountCreated); assertEquals("guest-secret", issued.ticket.guestAccess)
            assertEquals("EVT-123456", issued.ticket.manualCode.value); assertNull(store.token)
            assertFalse(auth.currentSession().state is SessionState.Authenticated)
            val req = withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }!!
            assertEquals(c.operationId,req.headers["Idempotency-Key"]); assertNull(req.headers["Authorization"])
            val body = req.body!!.utf8(); assertTrue(body.contains("\"bool\":false")); assertTrue(body.contains("\"many\":[\"a\",\"b\"]"))
            assertTrue(body.contains("\"phone\":\"\"")); assertFalse(body.contains("localSchemaRevision"))
        }
    }
    @Test fun lostResponseRetryUsesSameKeyAndBodyAndRejectsChangedBody() = runTest {
        MockWebServer().use { server -> server.start()
            val api = AuthHttp(server.url("/api/").toString(),OkHttpClient.Builder().readTimeout(50,TimeUnit.MILLISECONDS).build())
            val repo = RemoteRegistrations(api,RemoteAuthRepository(api,Store())); val c = command
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/json").body("{}").bodyDelay(300,TimeUnit.MILLISECONDS).build())
            assertEquals(RegistrationError.Timeout,(repo.submitGuest(c) as GuestRegistrationOutcome.Failed).error)
            assertEquals(RegistrationError.RecoveryRequired,(repo.submitGuest(c.copy(name="Changed")) as GuestRegistrationOutcome.Failed).error)
            server.enqueue(json("""{"ticket":$ticket,"accountCreated":true,"accessToken":"cap"}""",201))
            assertTrue(repo.submitGuest(c) is GuestRegistrationOutcome.Issued)
            val requests = withContext(Dispatchers.IO) { listOf(server.takeRequest(2,TimeUnit.SECONDS)!!,server.takeRequest(2,TimeUnit.SECONDS)!!) }
            assertEquals(requests[0].headers["Idempotency-Key"],requests[1].headers["Idempotency-Key"])
            assertEquals(requests[0].body!!.utf8(),requests[1].body!!.utf8())
        }
    }
    @Test fun existingEmailDoesNotExposeTicketOrCreateSession() = runTest {
        MockWebServer().use { server -> server.start(); server.enqueue(json("""{"code":"EMAIL_TAKEN","message":"exists"}""",409))
            val api = AuthHttp(server.url("/api/").toString()); val auth = RemoteAuthRepository(api,Store())
            assertEquals(GuestRegistrationOutcome.RequiresLogin("alex@example.com"),RemoteRegistrations(api,auth).submitGuest(command))
            assertFalse(auth.currentSession().state is SessionState.Authenticated)
        }
    }
    @Test fun lateGuestReplyAfterLoginDoesNotPublishReceiptOrOverwriteBearer() = runTest {
        MockWebServer().use { server -> server.start()
            val api = AuthHttp(server.url("/api/").toString()); val store = Store(); val auth = RemoteAuthRepository(api,store)
            server.enqueue(MockResponse.Builder().code(201).addHeader("Content-Type","application/json")
                .body("""{"ticket":$ticket,"accountCreated":true,"accessToken":"guest-cap"}""")
                .bodyDelay(500,TimeUnit.MILLISECONDS).build())
            val guest = async(Dispatchers.IO) { RemoteRegistrations(api,auth).submitGuest(command) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            server.enqueue(json("""{"token":"new-bearer","user":$user}"""))
            assertTrue(auth.signIn("alex@example.com",AuthSecret("password")) is AuthResult.Success)
            assertEquals(GuestRegistrationOutcome.Failed(RegistrationError.SessionChanged),guest.await())
            assertEquals("new-bearer",store.token!!.value); assertTrue(auth.currentSession().state is SessionState.Authenticated)
        }
    }
    @Test fun rateLimitRetainsBodyAndPreventsAnotherHttpAttemptUntilRetryAfter() = runTest {
        MockWebServer().use { server -> server.start()
            val api = AuthHttp(server.url("/api/").toString()); val repo = RemoteRegistrations(api,RemoteAuthRepository(api,Store())); val c=command
            server.enqueue(MockResponse.Builder().code(429).addHeader("Content-Type","application/json").addHeader("Retry-After","3600")
                .body("""{"code":"RATE_LIMITED","message":"wait"}""").build())
            assertTrue((repo.submitGuest(c) as GuestRegistrationOutcome.Failed).error is RegistrationError.RateLimited)
            assertTrue((repo.submitGuest(c) as GuestRegistrationOutcome.Failed).error is RegistrationError.RateLimited)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellationUsesReturnedStatusAndTicketUsedIsDistinct() = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString()); val auth=RemoteAuthRepository(api,Store())
            server.enqueue(json("""{"token":"bearer","user":$user}""")); auth.signIn("alex@example.com",AuthSecret("password"))
            val repo=RemoteTickets(api,auth)
            server.enqueue(json(ticket.replace("\"status\":\"active\"","\"status\":\"cancelled\"")))
            assertEquals(TicketStatus.CANCELLED,(repo.cancelOwnTicket(TicketId("t1")) as CancelTicketOutcome.Cancelled).ticket.status)
            server.enqueue(json("""{"code":"TICKET_USED","message":"used"}""",409))
            assertEquals(CancelTicketOutcome.Failed(TicketError.USED),repo.cancelOwnTicket(TicketId("t1")))
        }
    }
    @Test fun alreadyRegisteredWithoutTicketIdResolvesOnlyThroughOwnTickets() = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString()); val auth=RemoteAuthRepository(api,Store())
            server.enqueue(json("""{"token":"bearer","user":$user}""")); auth.signIn("alex@example.com",AuthSecret("password"))
            server.enqueue(json("""{"code":"ALREADY_REGISTERED","message":"exists"}""",409))
            server.enqueue(json("""{"items":[$ticket],"counters":{"upcoming":1,"past":0}}"""))
            assertEquals(AuthenticatedRegistrationOutcome.Registered(TicketId("t1"),true),RemoteRegistrations(api,auth)
                .submitAuthenticated(AuthenticatedRegistrationCommand(EventId("e1"),null,"Ignored",consentAccepted=true)))
        }
    }
    @Test fun scopesUseServerCountersAndSummaryDoesNotFabricateEventOwner() = runTest {
        MockWebServer().use { server -> server.start(); server.enqueue(json("""{"token":"bearer","user":$user}"""))
            val api = AuthHttp(server.url("/api/").toString()); val auth = RemoteAuthRepository(api,Store())
            assertTrue(auth.signIn("alex@example.com",AuthSecret("password")) is AuthResult.Success)
            server.enqueue(json("""{"items":[$ticket],"counters":{"upcoming":1,"past":4}}"""))
            val ready = RemoteTickets(api,auth).observeMyTickets(TicketScope.PAST).first() as MyTicketsResult.Ready
            assertEquals(TicketScope.PAST,ready.scope); assertEquals(4,ready.counters[TicketScope.PAST]); assertTrue(ready.events.isEmpty()); assertNull(ready.tickets.single().registrationId)
            assertEquals("конференция",ready.tickets.single().summary!!.slug.value)
            withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }
            val request = withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }!!
            assertEquals("/api/me/tickets?scope=past",request.url.encodedPath+"?"+request.url.encodedQuery)
            assertEquals("Bearer bearer",request.headers["Authorization"])
        }
    }
    @Test fun guestPdfUsesQueryCapabilityWithoutBearerAndRequiresPdfBeforeWriting() = runTest {
        MockWebServer().use { server -> server.start(); val api = AuthHttp(server.url("/api/").toString())
            val auth = RemoteAuthRepository(api,Store()); val repo = RemoteTicketDocuments(api,auth)
            val result = repo.prepare(DocumentRequest.TicketPdf(TicketId("t1"),"guest-secret")) as DocumentResult.Ready
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/pdf").body("%PDF-1.7\nsynthetic").build())
            val output = java.io.ByteArrayOutputStream(); (result.snapshot as DocumentSnapshot.Remote).source.writeTo(output)
            assertTrue(output.toString().startsWith("%PDF-"))
            val req = withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }!!
            assertNull(req.headers["Authorization"]); assertEquals("guest-secret",req.url.queryParameter("token"))
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","text/html").body("not pdf").build())
            val bad = java.io.ByteArrayOutputStream()
            try { (result.snapshot as DocumentSnapshot.Remote).source.writeTo(bad); fail("Expected protocol failure") } catch(e: DocumentFailure) { assertEquals(DocumentError.PROTOCOL,e.error); assertEquals(0,bad.size()) }
        }
    }
}
