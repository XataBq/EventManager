package ru.eventpass.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.profile.*

class RemoteAuthRepositoryTest {
 private class Store : SessionTokenStore {
  var value: AuthSecret? = null
  var broken = false
  override suspend fun read() = value
  override suspend fun write(token: AuthSecret) { if(broken) throw IOException("test disk"); value=token }
  override suspend fun clear() { value=null }
 }
 private val user = """{"id":"account-id","publicId":"123456","fullName":"User 李","email":"user@example.com","phone":null,"organization":null,"emailVerified":false,"notificationsEnabled":true}"""
 private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
 private fun session(token: String = "opaque-token") = """{"token":"$token","user":$user}"""
 @Test fun signupCreatesUnverifiedSessionWithNoInventedExpiryAndRedactedScope() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response(session(),201))
   val store=Store(); val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   val result=repo.signUp("User 李","user@example.com",AuthSecret("password"),true) as AuthResult.Success
   assertFalse(result.value.account.emailVerified); assertNull(result.value.expiresAt); assertNotEquals("opaque-token",result.value.id)
   val request=server.takeRequest(); assertEquals("/api/auth/register",request.url?.encodedPath); assertTrue(request.body?.utf8().orEmpty().contains("consentAccepted")); assertNull(request.headers["Authorization"])
   assertEquals("opaque-token",store.value?.value)
  }
 }
 @Test fun restoreChecksServerAndLogoutClearsEvenWhenServerFails() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response(user)); server.enqueue(response("{}",503))
   val store=Store().apply { value=AuthSecret("stored") }; val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   assertEquals(SessionState.Unknown,repo.currentSession().state); repo.refreshSession()
   assertTrue(repo.currentSession().state is SessionState.Authenticated)
   assertEquals("Bearer stored",server.takeRequest().headers["Authorization"])
   repo.signOut(); assertNull(store.value); assertEquals(SessionState.Guest,repo.currentSession().state)
  }
 }
 @Test fun bootstrapNetworkErrorIsRecoverableAndRetryUsesSameStoredToken() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response("{}",503)); server.enqueue(response(user))
   val store=Store().apply { value=AuthSecret("stored") }; val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   repo.refreshSession(); assertTrue(repo.currentSession().state is SessionState.Recovery); assertNotNull(store.value)
   repo.refreshSession(); assertTrue(repo.currentSession().state is SessionState.Authenticated)
  }
 }
 @Test fun protected401ClearsButLogin401DoesNotInvalidateAuthenticatedSession() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response(session())); server.enqueue(response("{\"code\":\"INVALID_CREDENTIALS\"}",401)); server.enqueue(response("{\"code\":\"UNAUTHORIZED\"}",401))
   val store=Store(); val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   repo.signIn("user@example.com",AuthSecret("password")); val original=(repo.currentSession().state as SessionState.Authenticated).session.id
   val failed=repo.signIn("user@example.com",AuthSecret("wrong")) as AuthResult.Failed
   assertEquals(AuthError.InvalidCredentials,failed.error); assertEquals(original,(repo.currentSession().state as SessionState.Authenticated).session.id)
   repo.refreshSession(); assertEquals(SessionState.Guest,repo.currentSession().state); assertNull(store.value)
  }
 }
 @Test fun staleProtected401CannotEvictNewLogin() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response(session("old")))
   val store=Store(); val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store); repo.signIn("user@example.com",AuthSecret("password")); server.takeRequest()
   server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type","application/json").body("{\"code\":\"UNAUTHORIZED\"}").headersDelay(250,TimeUnit.MILLISECONDS).build())
   val old=async(Dispatchers.Default) { repo.refreshSession() }; withContext(Dispatchers.IO) { server.takeRequest() }
   server.enqueue(response(session("new"))); repo.signIn("user@example.com",AuthSecret("password")); old.await()
   assertEquals("new",store.value?.value); assertTrue(repo.currentSession().state is SessionState.Authenticated)
  }
 }
 @Test fun malformedSuccessfulResponseAndFailedDurabilityNeverAuthorize() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response("{}")); server.enqueue(response(session()))
   val store=Store(); val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   assertEquals(AuthError.Protocol,(repo.signIn("e",AuthSecret("p")) as AuthResult.Failed).error)
   store.broken=true; assertEquals(AuthError.StorageUnavailable,(repo.signIn("e",AuthSecret("p")) as AuthResult.Failed).error)
   assertFalse(repo.currentSession().state is SessionState.Authenticated); assertNull(store.value)
  }
 }
 @Test fun empty204EmailDispatchDoesNotInventChallengeAndConsentCanBeNull() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(MockResponse.Builder().code(204).build()); server.enqueue(response("{\"terms\":{\"url\":null,\"version\":null},\"privacy\":{\"url\":null,\"version\":null}}"))
   val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),Store())
   assertNull((repo.requestPasswordReset("e@example.com") as AuthResult.Success).value.resendAt)
   assertNull((repo.consentDocuments() as AuthResult.Success).value.terms.url)
  }
 }
 @Test fun errorsRemainTypedAndRetryAfterIsHonored() = runTest {
  MockWebServer().use { server -> server.start()
   for((code,error) in listOf(403 to AuthError.Forbidden,422 to AuthError.InvalidInput,503 to AuthError.ServerUnavailable)) {
    server.enqueue(response("{}",code)); val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),Store())
    assertEquals(error,(repo.signIn("e",AuthSecret("p")) as AuthResult.Failed).error)
   }
   server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After","5").body("{}").build())
   val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),Store())
   assertTrue((repo.signIn("e",AuthSecret("p")) as AuthResult.Failed).error is AuthError.Cooldown)
  }
 }
 @Test fun timeoutAndCancellationDoNotAuthorizeOrRetry() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(MockResponse.Builder().body(session()).headersDelay(300,TimeUnit.MILLISECONDS).build())
   val client=OkHttpClient.Builder().readTimeout(50,TimeUnit.MILLISECONDS).build()
   val store=Store(); val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString(),client),store)
   assertEquals(AuthError.Timeout,(repo.signIn("e",AuthSecret("p")) as AuthResult.Failed).error)
   server.enqueue(MockResponse.Builder().body(session()).headersDelay(300,TimeUnit.MILLISECONDS).build())
   val job=launch(Dispatchers.Default) { repo.signIn("e",AuthSecret("p")) }; delay(25); job.cancelAndJoin()
   assertNull(store.value); assertFalse(repo.currentSession().state is SessionState.Authenticated)
  }
 }
 @Test fun foreignRedirectNeverReceivesBearer() = runTest {
  MockWebServer().use { origin -> MockWebServer().use { foreign -> origin.start(); foreign.start()
   origin.enqueue(MockResponse.Builder().code(302).addHeader("Location",foreign.url("/stolen")).build())
   val store=Store().apply{value=AuthSecret("secret")}; val repo=RemoteAuthRepository(AuthHttp(origin.url("/api/").toString()),store)
   repo.refreshSession(); assertTrue(repo.currentSession().state is SessionState.Recovery); assertEquals(0,foreign.requestCount)
  } }
 }
 @Test fun cancelledDurableWriteCompensatesBeforeAnyFutureBootstrap() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response(session()))
   val entered=CompletableDeferred<Unit>()
   var saved: AuthSecret?=null
   val store=object: SessionTokenStore {
    override suspend fun read()=saved
    override suspend fun write(token: AuthSecret) { saved=token; entered.complete(Unit); awaitCancellation() }
    override suspend fun clear() { saved=null }
   }
   val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   val job=launch(Dispatchers.Default) { repo.signIn("e",AuthSecret("p")) }
   entered.await(); job.cancelAndJoin()
   assertNull(saved); assertFalse(repo.currentSession().state is SessionState.Authenticated)
   val restarted=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store); restarted.refreshSession()
   assertEquals(SessionState.Guest,restarted.currentSession().state)
  }
 }
 @Test fun logoutInvalidatesAcceptanceBeforeWaitingForStoreLock() = runTest {
  MockWebServer().use { server -> server.start(); server.enqueue(response(session()))
   val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var saved: AuthSecret?=null
   val store=object: SessionTokenStore {
    override suspend fun read()=saved
    override suspend fun write(token: AuthSecret) { saved=token; entered.complete(Unit); release.await() }
    override suspend fun clear() { saved=null }
   }
   val repo=RemoteAuthRepository(AuthHttp(server.url("/api/").toString()),store)
   val login=async(Dispatchers.Default) { repo.signIn("e",AuthSecret("p")) }; entered.await()
   val logout=launch { repo.signOut() }; yield(); release.complete(Unit); logout.join()
   assertEquals(AuthError.SessionChanged,(login.await() as AuthResult.Failed).error)
   assertNull(saved); assertEquals(SessionState.Guest,repo.currentSession().state)
  }
 }

}
