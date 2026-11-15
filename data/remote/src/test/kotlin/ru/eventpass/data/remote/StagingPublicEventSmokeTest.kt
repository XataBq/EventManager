package ru.eventpass.data.remote

import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*

/** Synthetic owner/event only; opt-in real adapter check, not a human acceptance. */
class StagingPublicEventSmokeTest {
    @Test fun realUnicodeSlugReadsIndependentViewerAndCleansOwnAccount() = runBlocking {
        assumeTrue(System.getenv("EVENTPASS_STAGING_SMOKE") == "1")
        val api = AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
        var secret: AuthSecret? = null
        fun store() = object : SessionTokenStore {
            override suspend fun read() = secret
            override suspend fun write(token: AuthSecret) { secret = token }
            override suspend fun clear() { secret = null }
        }
        val auth = RemoteAuthRepository(api, store())
        val email = "android.m02.${UUID.randomUUID()}@example.com"
        val password = AuthSecret(UUID.randomUUID().toString())
        val unavailable = Proxy.newProxyInstance(EventRepository::class.java.classLoader, arrayOf(EventRepository::class.java)) {
            _, method, _ -> error("Future operation: ${method.name}")
        } as EventRepository
        try {
            assertTrue(auth.signUp("Android M02 audit", email, password, true) is AuthResult.Success)
            val body = buildJsonObject {
                put("title", "Тест мероприятия ${UUID.randomUUID()}"); put("description", "Синтетическая проверка Android M02")
                put("location", "Staging"); put("startsAt", Instant.now().minusSeconds(3600).toString())
                put("capacity", 3); put("publish", true)
                put("questions", buildJsonArray { add(buildJsonObject {
                    put("id", "подтверждение"); put("type", "checkbox"); put("label", "Участие"); put("required", false)
                }) })
            }.toString()
            val created = api.json.parseToJsonElement(api.request("events", body, secret)).jsonObject
            val reference = PublicEventReference.Slug(EventSlug(created.getValue("slug").jsonPrimitive.content))
            val found = RemoteEventRepository(api, auth, unavailable).observePublicEvent(reference).first() as PublicEventResult.Found
            assertEquals(created.getValue("id").jsonPrimitive.content, found.event.id.value)
            assertEquals(reference.slug, found.event.slug); assertEquals(EventLifecycle.LIVE, found.event.lifecycle)
            assertTrue(found.access.canManage && found.access.canScan); assertTrue(found.availability is RegistrationAvailability.Open)
            assertNull(found.event.localSchemaRevision); assertNull(found.event.revision)
            val anonymous = RemoteAuthRepository(api, object : SessionTokenStore {
                override suspend fun read(): AuthSecret? = null
                override suspend fun write(token: AuthSecret) = Unit
                override suspend fun clear() = Unit
            })
            val guest = RemoteEventRepository(api, anonymous, unavailable).observePublicEvent(reference).first() as PublicEventResult.Found
            assertFalse(guest.access.canManage || guest.access.canScan); assertNull(guest.contact)
        } finally {
            if (secret == null) auth.signIn(email, password)
            secret?.let { api.request("me", token = it, method = "DELETE") }
            auth.signOut()
        }
    }
}
