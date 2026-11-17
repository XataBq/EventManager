package ru.eventpass.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*

class RemoteOrganizerRepositoryTest {
    @Test fun localStorageLimitsReturnFieldValidationWithoutSendingInvalidDraft()=runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable);val id=DraftId("invalid");repo.beginDraft(id)
            val result=repo.saveDraft(id,null,EventFields(title="x".repeat(301))) as EventEditResult.Failed
            assertEquals(EventEditError.Validation(setOf("title")),result.error);assertEquals(1,server.requestCount)
        }
    }
    @Test fun serverPublishedStateIsNotReclassifiedByDeviceClock()=runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable)
            server.enqueue(json(published().replace("2026-11-17","2000-01-01")))
            val value=repo.observeEditor(EventEditorTarget.Published(EventId("server-id"))).first() as EventEditorResult.Published
            assertFalse(value.terminal);assertEquals(EventLifecycle.PUBLISHED,value.event.lifecycle)
        }
    }
    @Test fun deleteWithoutBodyStillKeepsItsTransportContract() = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString())
            server.enqueue(json("{}")); api.request("me",method="DELETE")
            val request=withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }!!
            assertEquals("DELETE",request.method); assertEquals(0L,request.bodySize)
        }
    }
    private class Store : SessionTokenStore {
        var token: AuthSecret? = null
        override suspend fun read() = token
        override suspend fun write(token: AuthSecret) { this.token = token }
        override suspend fun clear() { token = null }
    }
    private val unavailable = object : EventRepository {
        override fun observePublicEvent(reference: PublicEventReference) = emptyFlow<PublicEventResult>()
        override suspend fun refreshPublicEvent(reference: PublicEventReference) {}
        override fun observeMyEvents() = emptyFlow<MyEventsResult>()
        override fun observeEditor(target: EventEditorTarget) = emptyFlow<EventEditorResult>()
        override suspend fun refreshMyEvents() {}
        override suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
        override suspend fun publishDraft(id: DraftId, expectedRevision: Long?) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
        override suspend fun updateEvent(id: EventId, expectedRevision: Long?, expectedLocalSchemaRevision: Int?, fields: EventFields) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
        override suspend fun finishEvent(id: EventId, expectedRevision: Long?) = EventEditResult.Failed(EventEditError.FeatureUnavailable)
    }
    private val user = """{"id":"actor","publicId":"123456","fullName":"Alex","email":"alex@example.com","phone":null,"organization":null,"emailVerified":false,"notificationsEnabled":true}"""
    private val draft = """{"id":"server-id","slug":"server-slug","organizerId":"actor","title":"","status":"draft","startsAt":null,"endsAt":null,"effectiveEndsAt":null,"finishedAt":null,"location":"","capacity":null,"registeredCount":0,"checkedInCount":0,"description":"","priceType":"free","questions":[],"sections":[],"coverAssetId":null,"coverUrl":null}"""
    private fun json(text: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type","application/json").body(text).build()
    private suspend fun login(server: MockWebServer, auth: RemoteAuthRepository) {
        server.enqueue(json("""{"token":"bearer","user":$user}"""))
        assertTrue(auth.signIn("alex@example.com",AuthSecret("password")) is AuthResult.Success)
        withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }
    }
    @Test fun incompleteDraftCreateReadAndPatchKeepNullsAndOmissionDistinct() = runTest {
        MockWebServer().use { server -> server.start()
            val api = AuthHttp(server.url("/api/").toString()); val auth = RemoteAuthRepository(api,Store()); login(server,auth)
            val repo = RemoteOrganizerRepository(api,auth,unavailable); val local = DraftId("local-draft")
            repo.beginDraft(local)
            server.enqueue(json(draft,201))
            val saved = repo.saveDraft(local,null,EventFields()) as EventEditResult.Saved
            assertNull(saved.value.revision); assertNull(saved.value.fields.startsAt); assertNull(saved.value.fields.capacity)
            val create = withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }!!
            assertEquals("POST",create.method); assertNotNull(create.headers["Idempotency-Key"])
            assertTrue(create.body!!.utf8().contains("\"publish\":false"))
            server.enqueue(json(draft.replace("\"title\":\"\"","\"title\":\"Changed\"")))
            assertTrue(repo.saveDraft(local,null,EventFields(title="Changed")) is EventEditResult.Saved)
            val patch = withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }!!
            assertEquals("PATCH",patch.method); assertEquals("/api/events/server-id",patch.url.encodedPath)
            assertEquals("{\"title\":\"Changed\"}",patch.body!!.utf8())
            server.enqueue(json(draft))
            val read = repo.observeEditor(EventEditorTarget.Draft(DraftId("server-id"))).first() as EventEditorResult.Draft
            assertNull(read.draft.fields.startsAt); assertEquals(DraftId("server-id"),read.draft.id)
        }
    }
    @Test fun explicitNullIsEncodedWhileUnchangedFieldsAreOmittedAndIdsStayStable() {
        val fields = EventFields(endsAt=java.time.Instant.EPOCH,remoteCoverAssetId="asset",sections=listOf(EventSection("","","stable")))
        val before = encodeFields(fields); val after = encodeFields(fields.copy(endsAt=null,remoteCoverAssetId=null))
        assertEquals(kotlinx.serialization.json.JsonNull,after["endsAt"])
        assertEquals(kotlinx.serialization.json.JsonNull,after["coverAssetId"])
        assertEquals(before["sections"],after["sections"])
        assertEquals(setOf("endsAt","coverAssetId"),after.filter { (k,v) -> before[k] != v }.keys)
    }
    @Test fun lostCreateResponseReplaysSameKeyBodyAndDoesNotCreateForChangedInput() = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString(),okhttp3.OkHttpClient.Builder().readTimeout(150,TimeUnit.MILLISECONDS).build())
            val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable);val id=DraftId("transient-local")
            repo.beginDraft(id)
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/json").body(draft).bodyDelay(600,TimeUnit.MILLISECONDS).build())
            assertEquals(EventEditError.Timeout,(repo.saveDraft(id,null,EventFields()) as EventEditResult.Failed).error)
            assertTrue(repo.saveDraft(id,null,EventFields(title="Different")) is EventEditResult.Failed)
            server.enqueue(json(draft,201))
            assertTrue(repo.saveDraft(id,null,EventFields()) is EventEditResult.Saved)
            val requests=withContext(Dispatchers.IO) { listOf(server.takeRequest(2,TimeUnit.SECONDS)!!,server.takeRequest(2,TimeUnit.SECONDS)!!) }
            assertEquals(requests[0].headers["Idempotency-Key"],requests[1].headers["Idempotency-Key"])
            assertEquals(requests[0].body!!.utf8(),requests[1].body!!.utf8());assertEquals(3,server.requestCount)
        }
    }
    @Test fun scopedDraftListRetainsNullableSummaryCountersAndDoesNotReadHiddenTabs() = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable)
            val item="""{"id":"d","slug":"draft","title":"","status":"draft","startsAt":null,"endsAt":null,"effectiveEndsAt":null,"finishedAt":null,"location":"","capacity":null,"registeredCount":0,"checkedInCount":0}"""
            server.enqueue(json("""{"roles":["organizer"],"items":[$item],"counters":{"drafts":1,"organizing":0,"volunteering":0}}"""))
            val ready=repo.observeMyEvents(MyEventsTab.DRAFTS).first() as MyEventsResult.Ready
            assertEquals(MyEventsTab.DRAFTS,ready.tab);assertNull(ready.items.single().startsAt)
            assertTrue(ready.organizing.isEmpty());assertEquals(1,ready.counters[MyEventsTab.DRAFTS])
            val request=withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)}!!
            assertEquals("drafts",request.url.queryParameter("tab"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun lateCreateAfterLogoutCannotPublishPrivateDraft() = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable);val id=DraftId("local-logout");repo.beginDraft(id)
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/json").body(draft).bodyDelay(400,TimeUnit.MILLISECONDS).build())
            val saving=async(Dispatchers.IO) {repo.saveDraft(id,null,EventFields())}
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            server.enqueue(json("{}"));auth.signOut()
            assertTrue(saving.await() is EventEditResult.Failed)
            assertEquals(EventEditError.AuthenticationRequired,(repo.observeEditor(EventEditorTarget.Draft(id)).first() as EventEditorResult.Failed).error)
        }
    }
    private fun published(status:String="published")=draft.replace("\"status\":\"draft\"","\"status\":\"$status\"")
        .replace("\"title\":\"\"","\"title\":\"Event\"").replace("\"location\":\"\"","\"location\":\"Hall\"")
        .replace("\"startsAt\":null","\"startsAt\":\"2026-11-17T10:00:00Z\"")
        .replace("\"effectiveEndsAt\":null","\"effectiveEndsAt\":\"2026-11-17T14:00:00Z\"")
        .replace("\"capacity\":null","\"capacity\":10")
    @Test fun publishAndFinishUseSameServerIdAndPreserveDefaultEndAndManualFinish()=runTest {
        MockWebServer().use {server->server.start();val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable);val id=DraftId("local-publish");repo.beginDraft(id)
            server.enqueue(json(draft));assertTrue(repo.saveDraft(id,null,EventFields()) is EventEditResult.Saved)
            withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)}
            repeat(2) {
                server.enqueue(json(published()))
                assertEquals(EventId("server-id"),(repo.publishDraft(id,null) as EventEditResult.Saved).value)
                val request=withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)}!!
                assertEquals("/api/events/server-id/publish",request.url.encodedPath)
            }
            val finishedAt="2026-11-17T11:00:00Z"
            server.enqueue(json(published("finished").replace("\"finishedAt\":null","\"finishedAt\":\"$finishedAt\"")))
            val finished=(repo.finishEvent(EventId("server-id"),null) as EventEditResult.Saved).value
            assertNull(finished.revision);assertEquals(java.time.Instant.parse(finishedAt),finished.finishedAt)
            assertEquals(java.time.Instant.parse("2026-11-17T14:00:00Z"),finished.effectiveEndsAt)
        }
    }
    @Test fun uncertainPatchRequiresExplicitReadBeforeAnotherMutation()=runTest {
        MockWebServer().use {server->server.start();val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable);val target=EventEditorTarget.Draft(DraftId("server-id"))
            server.enqueue(json(draft));assertTrue(repo.observeEditor(target).first() is EventEditorResult.Draft)
            server.enqueue(json("""{"code":"INTERNAL","message":"temporary"}""",503))
            assertTrue(repo.saveDraft(target.id,null,EventFields(title="Changed")) is EventEditResult.Failed)
            val count=server.requestCount
            assertTrue(repo.saveDraft(target.id,null,EventFields(title="Changed")) is EventEditResult.Failed)
            assertEquals(count,server.requestCount)
            server.enqueue(json(draft));repo.refreshEditor(target)
            server.enqueue(json(draft.replace("\"title\":\"\"","\"title\":\"Changed\"")))
            assertTrue(repo.saveDraft(target.id,null,EventFields(title="Changed")) is EventEditResult.Saved)
        }
    }
    @Test fun allTenQuestionTypesAndStableOptionSectionIdsSurviveDraftRoundTrip()=runTest {
        MockWebServer().use {server->server.start();val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            val repo=RemoteOrganizerRepository(api,auth,unavailable);val id=DraftId("questions");repo.beginDraft(id)
            val questions=QuestionKind.entries.filter {it != QuestionKind.UNSUPPORTED}.map {kind->
                DraftQuestion(kind.name,"Вопрос",true,kind,listOf(ru.eventpass.core.domain.registration.RegistrationQuestion.Option("stable-a","Да","Подсказка"),ru.eventpass.core.domain.registration.RegistrationQuestion.Option("stable-b","Нет")),"Введите","Помощь")
            }
            val fields=EventFields(questions=questions,sections=listOf(EventSection("Программа","10:00","section-stable")))
            val encoded=encodeFields(fields)
            val dto=kotlinx.serialization.json.Json.parseToJsonElement(draft) as kotlinx.serialization.json.JsonObject
            val response=kotlinx.serialization.json.JsonObject(dto+mapOf("questions" to encoded.getValue("questions"),"sections" to encoded.getValue("sections"))).toString()
            server.enqueue(json(response))
            val actual=(repo.saveDraft(id,null,fields) as EventEditResult.Saved).value.fields
            assertEquals(questions.map {it.id to it.kind},actual.questions.map {it.id to it.kind})
            assertEquals(fields.sections,actual.sections)
            assertEquals(listOf("stable-a","stable-b"),actual.questions.first {it.kind == QuestionKind.SELECT}.options.map {it.id})
            assertEquals("Подсказка",actual.questions.first {it.kind == QuestionKind.MULTIPLE}.options[0].hint)
        }
    }
}
