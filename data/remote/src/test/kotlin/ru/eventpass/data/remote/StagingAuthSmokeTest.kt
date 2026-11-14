package ru.eventpass.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import java.util.UUID

/** Opt-in, isolated account, no credentials in logs/files. Not a human UI acceptance. */
class StagingAuthSmokeTest {
 @Test fun realSignupLoginProfileAndCleanup() = runBlocking {
  assumeTrue(System.getenv("EVENTPASS_STAGING_SMOKE") == "1")
  val api=AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
  var secret: AuthSecret?=null
  val store=object: SessionTokenStore { override suspend fun read()=secret; override suspend fun write(token:AuthSecret) { secret=token }; override suspend fun clear() { secret=null } }
  val repo=RemoteAuthRepository(api,store)
  val email="android.m01."+UUID.randomUUID().toString()+"@example.com"
  val password=AuthSecret(UUID.randomUUID().toString())
  try {
   assertTrue(repo.signUp("Android M01 audit",email,password,true) is AuthResult.Success)
   assertFalse((repo.currentSession().state as SessionState.Authenticated).session.account.emailVerified)
   repo.refreshSession(); assertTrue(repo.currentSession().state is SessionState.Authenticated)
   repo.signOut(); assertEquals(SessionState.Guest,repo.currentSession().state)
   assertTrue(repo.signIn(email,password) is AuthResult.Success)
   repo.refreshSession(); assertTrue(repo.profileRepository.refreshProfile() is ru.eventpass.core.domain.profile.ProfileResult.Ready)
  } finally {
   if(secret==null) repo.signIn(email,password)
   secret?.let { api.request("me",token=it,method="DELETE") }
   repo.signOut()
  }
 }
}
