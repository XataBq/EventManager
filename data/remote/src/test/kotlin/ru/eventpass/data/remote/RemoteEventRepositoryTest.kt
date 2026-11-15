package ru.eventpass.data.remote

import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

class RemoteEventRepositoryTest {
    private class Store : SessionTokenStore {
        var value: AuthSecret? = null
        override suspend fun read() = value
        override suspend fun write(token: AuthSecret) { value = token }
        override suspend fun clear() { value = null }
    }
    private fun unavailable() = Proxy.newProxyInstance(EventRepository::class.java.classLoader,
        arrayOf(EventRepository::class.java)) { _, method, _ -> error("Unexpected future operation: ${method.name}") } as EventRepository
    private val reference = PublicEventReference.Slug(EventSlug("встреча-123"))
    private fun response(body: String, status: Int = 200, delay: Long = 0) = MockResponse.Builder()
        .code(status).addHeader("Content-Type", "application/json").body(body).headersDelay(delay, TimeUnit.MILLISECONDS).build()
    private fun fixture(status: String = "live", registered: Int = 2, owner: Boolean = false, volunteer: Boolean = false,
        ticket: String? = null, questions: JsonArray = JsonArray(emptyList())) = buildJsonObject {
        put("event", buildJsonObject {
            put("id", "internal-event"); put("slug", reference.slug.value); put("organizerId", "actor")
            put("title", "Встреча"); put("description", "Описание"); put("status", status)
            put("startsAt", "2026-01-01T12:00:00Z"); put("endsAt", JsonNull)
            put("effectiveEndsAt", "2026-01-01T16:00:00Z"); put("finishedAt", JsonNull)
            put("location", "Москва"); put("capacity", 3); put("registeredCount", registered); put("checkedInCount", 1)
            put("priceType", "paid"); put("questions", questions)
            put("sections", buildJsonArray { add(buildJsonObject { put("id", "section-id"); put("title", "Начало"); put("time", "15:00") }) })
            put("coverAssetId", "asset"); put("coverUrl", "/api/assets/event-covers/asset")
        })
        put("viewer", buildJsonObject { put("ticketId", ticket?.let(::JsonPrimitive) ?: JsonNull); put("isOrganizer", owner); put("isVolunteer", volunteer) })
    }.toString()
    private fun changed(body: String, key: String, value: JsonElement): String {
        val root = Json.parseToJsonElement(body).jsonObject
        return JsonObject(root + ("event" to JsonObject(root.getValue("event").jsonObject + (key to value)))).toString()
    }
    private suspend fun request(server: MockWebServer) = withContext(Dispatchers.IO) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
    private fun repo(server: MockWebServer, auth: RemoteAuthRepository? = null): RemoteEventRepository {
        val api = AuthHttp(server.url("/api/").toString())
        return RemoteEventRepository(api, auth ?: RemoteAuthRepository(api, Store()), unavailable())
    }
    private val user = """{"id":"actor","publicId":"123456","fullName":"User","email":"user@example.com","phone":null,"organization":null,"emailVerified":false,"notificationsEnabled":true}"""

    @Test fun unicodeSlugDoesNotBecomeInternalIdOrInventServerMetadata() = runTest {
        MockWebServer().use { server -> server.start(); server.enqueue(response(fixture()))
            val result = repo(server).observePublicEvent(reference).first() as PublicEventResult.Found
            assertEquals("internal-event", result.event.id.value); assertEquals(reference.slug, result.event.slug)
            assertEquals("/api/events/%D0%B2%D1%81%D1%82%D1%80%D0%B5%D1%87%D0%B0-123", request(server).url.encodedPath)
            assertNull(result.event.localSchemaRevision); assertNull(result.event.revision); assertNull(result.event.cover)
            assertEquals("asset", result.event.remoteCoverAssetId); assertEquals("section-id", result.event.sections.single().id)
            assertEquals(PriceType.PAID, result.event.priceType); assertEquals(2, result.registeredCount)
            // Client wall-clock does not turn a server live response into a locally invented finished state.
            assertTrue(result.availability is RegistrationAvailability.Open)
        }
    }
    @Test fun internalReferenceIsExplicitlyUnavailableWithoutNetwork() = runTest {
        MockWebServer().use { s -> s.start()
            assertEquals(PublicEventResult.Failed(ProductReadError.FEATURE_UNAVAILABLE), repo(s).observePublicEvent(EventId("id")).first())
            assertEquals(0, s.requestCount)
        }
    }
    @Test fun allWireTypesAndUnsupportedPreserveMetadata() = runTest {
        val types = listOf("text", "textarea", "email", "tel", "number", "date", "choice", "select", "multiple", "checkbox", "future")
        val questions = buildJsonArray { types.forEach { type -> add(buildJsonObject {
            put("id", type); put("label", "Вопрос"); put("type", type); put("required", false)
            put("help", "Подсказка"); put("placeholder", "Введите")
            if (type in listOf("choice", "select", "multiple")) put("options", buildJsonArray {
                listOf("a", "b").forEach { add(buildJsonObject { put("value", it); put("label", "Same label"); put("hint", "Hint") }) }
            })
        }) } }
        MockWebServer().use { s -> s.start(); s.enqueue(response(fixture(questions = questions)))
            val q = (repo(s).observePublicEvent(reference).first() as PublicEventResult.Found).event.questions
            assertEquals(types.size, q.size); assertTrue(q.take(6).all { it is RegistrationQuestion.Text })
            assertEquals(TextFormat.DATE, (q[5] as RegistrationQuestion.Text).format)
            assertEquals(ChoiceFormat.SELECT, (q[7] as RegistrationQuestion.SingleChoice).format)
            assertEquals("Hint", (q[8] as RegistrationQuestion.MultipleChoice).options[0].hint)
            assertTrue(q[9] is RegistrationQuestion.Checkbox); assertEquals("future", (q[10] as RegistrationQuestion.Unsupported).rawType)
            assertTrue(q.all { it.help == "Подсказка" && it.placeholder == "Введите" })
        }
    }
    @Test fun publishedNullFieldsDraftOrMalformedResponsesAreProtocolNotFabricatedDefaults() = runTest {
        val bad = listOf(changed(fixture(), "startsAt", JsonNull), changed(fixture(), "capacity", JsonNull),
            changed(fixture(), "capacity", JsonPrimitive(0)), fixture(status = "draft"), "{}",
            changed(fixture(), "effectiveEndsAt", JsonPrimitive("2026-01-01T17:00:00Z")))
        for (body in bad) MockWebServer().use { s -> s.start(); s.enqueue(response(body))
            assertEquals(PublicEventResult.Failed(ProductReadError.PROTOCOL), repo(s).observePublicEvent(reference).first())
        }
    }
    @Test fun serverLegalLargeFormDoesNotHitTheSmallerAuthResponseLimit() = runTest {
        val questions = buildJsonArray { repeat(40) { q -> add(buildJsonObject {
            put("id", "question-$q"); put("label", "Question"); put("type", "select"); put("required", false)
            put("options", buildJsonArray { repeat(50) { option -> add(buildJsonObject {
                put("value", "value-$option"); put("label", "L".repeat(300)); put("hint", "H".repeat(300))
            }) } })
        }) } }
        val body = fixture(questions = questions)
        assertTrue(body.toByteArray().size > 1_048_576)
        MockWebServer().use { s -> s.start(); s.enqueue(response(body))
            val found = repo(s).observePublicEvent(reference).first() as PublicEventResult.Found
            assertEquals(40, found.event.questions.size)
            assertEquals(50, (found.event.questions.first() as RegistrationQuestion.SingleChoice).options.size)
        }
    }
    @Test fun notFoundForbiddenAndServerErrorRemainDifferent() = runTest {
        for ((status, expected) in listOf(404 to PublicEventResult.NotFound,
            403 to PublicEventResult.Failed(ProductReadError.FORBIDDEN), 503 to PublicEventResult.Failed(ProductReadError.SERVER_UNAVAILABLE))) {
            MockWebServer().use { s -> s.start(); s.enqueue(response("{}", status))
                assertEquals(expected, repo(s).observePublicEvent(reference).first())
            }
        }
    }
    @Test fun liveFullAndFinishedUseServerStatusAndCounts() = runTest {
        for ((status, count, reason) in listOf(Triple("live", 4, RegistrationClosureReason.FULL), Triple("finished", 2, RegistrationClosureReason.FINISHED))) {
            MockWebServer().use { s -> s.start(); s.enqueue(response(fixture(status, count)))
                val found = repo(s).observePublicEvent(reference).first() as PublicEventResult.Found
                assertEquals(RegistrationAvailability.Closed(reason), found.availability)
            }
        }
    }
    @Test fun independentServerViewerCapabilitiesRequireValidatedSession() = runTest {
        MockWebServer().use { s -> s.start(); s.enqueue(response("""{"token":"secret","user":$user}"""))
            s.enqueue(response(fixture(owner = true, volunteer = true, ticket = "own-ticket")))
            val api = AuthHttp(s.url("/api/").toString()); val auth = RemoteAuthRepository(api, Store())
            assertTrue(auth.signIn("user@example.com", AuthSecret("p")) is AuthResult.Success)
            val found = repo(s, auth).observePublicEvent(reference).first() as PublicEventResult.Found
            request(s); assertEquals("Bearer secret", request(s).headers["Authorization"])
            assertTrue(found.access.canManage && found.access.canScan && found.access.canViewOwnTicket)
            assertFalse(found.access.canRegister); assertEquals(TicketId("own-ticket"), found.ownTicketId)
            assertEquals("User", found.contact?.name)
        }
        MockWebServer().use { s -> s.start(); s.enqueue(response(fixture(owner = true, volunteer = true, ticket = "foreign")))
            val found = repo(s).observePublicEvent(reference).first() as PublicEventResult.Found
            assertFalse(found.access.canManage || found.access.canScan || found.access.canViewOwnTicket); assertNull(found.ownTicketId)
        }
    }
    @Test fun refreshIsSingleFlightForOneResource() = runTest {
        MockWebServer().use { s -> s.start(); s.enqueue(response(fixture(), delay = 250))
            val repo = repo(s); val first = async { repo.observePublicEvent(reference).first() }
            request(s)
            List(10) { launch { repo.refreshPublicEvent(reference) } }.joinAll()
            assertTrue(first.await() is PublicEventResult.Found); assertEquals(1, s.requestCount)
        }
    }
    @Test fun rejectedCurrentBearerIsClearedBeforePublicGuestRecovery() = runTest {
        MockWebServer().use { s -> s.start()
            s.enqueue(response("""{"token":"expired-later","user":$user}"""))
            s.enqueue(response("{\"code\":\"UNAUTHORIZED\"}", 401)); s.enqueue(response(fixture()))
            val store = Store(); val api = AuthHttp(s.url("/api/").toString()); val auth = RemoteAuthRepository(api, store)
            auth.signIn("user@example.com", AuthSecret("p"))
            val found = repo(s, auth).observePublicEvent(reference).first() as PublicEventResult.Found
            assertEquals(SessionState.Guest, auth.currentSession().state); assertNull(store.value)
            assertFalse(found.access.canManage || found.access.canViewOwnTicket); assertNull(found.contact)
            request(s); assertEquals("Bearer expired-later", request(s).headers["Authorization"])
            assertNull(request(s).headers["Authorization"])
        }
    }
    @Test fun logoutImmediatelyRemovesPrivateViewerWhileGuestRefreshIsDelayed() = runTest {
        MockWebServer().use { s -> s.start(); s.enqueue(response("""{"token":"secret","user":$user}"""))
            s.enqueue(response(fixture(owner = true, ticket = "own")))
            val api = AuthHttp(s.url("/api/").toString()); val auth = RemoteAuthRepository(api, Store())
            auth.signIn("user@example.com", AuthSecret("p")); val repo = repo(s, auth)
            val values = mutableListOf<PublicEventResult.Found>()
            val job = launch { repo.observePublicEvent(reference).filterIsInstance<PublicEventResult.Found>().collect { values.add(it) } }
            withTimeout(5000) { while (values.none { it.access.canManage }) delay(10) }
            // Either ordering of logout POST and guest GET is harmless: both receive valid JSON, with no private viewer.
            s.enqueue(response(fixture(), delay = 250)); s.enqueue(response(fixture(), delay = 250))
            val logout = launch(Dispatchers.Default) { auth.signOut() }
            withTimeout(5000) { while (values.last().access.canManage || values.last().ownTicketId != null) delay(10) }
            assertNull(values.last().contact); assertFalse(values.last().access.canScan)
            logout.join(); job.cancelAndJoin()
        }
    }
}
