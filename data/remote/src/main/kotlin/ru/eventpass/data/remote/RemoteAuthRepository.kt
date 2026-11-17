package ru.eventpass.data.remote

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.profile.*

class RemoteAuthRepository(private val api: AuthHttp, private val tokens: SessionTokenStore,
 private val onExplicitLogout:suspend (ru.eventpass.core.domain.event.UserId)->Unit = {}) : AuthRepository {
 private val lock = Mutex()
 private val mutable = MutableStateFlow(SessionSnapshot(0, SessionState.Unknown))
 private var token: AuthSecret? = null
 private var initialized = false
 private val epoch = AtomicLong()
 private var user: UserDto? = null
 private val profiles = MutableStateFlow<ProfileResult>(ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED))
 override fun currentSession() = mutable.value
 override fun observeSession(): Flow<SessionSnapshot> = mutable.asStateFlow()
 private fun publish(state: SessionState) { mutable.value = SessionSnapshot(epoch.get(), state) }
 private fun clearMemory() { token = null; user = null; profiles.value = ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED) }
 /** Captures only an already validated session. Guest/recovery never reads a stored bearer. */
 internal suspend fun capturePublicRead(): PublicReadContext = lock.withLock {
  PublicReadContext(mutable.value, epoch.get(), if(mutable.value.state is SessionState.Authenticated) token else null,
   if(mutable.value.state is SessionState.Authenticated) user?.profile() else null)
 }
 internal fun isCurrentPublicRead(context: PublicReadContext): Boolean =
  mutable.value == context.snapshot && epoch.get() == context.epoch && (context.token == null || token === context.token)
 internal suspend fun requestPublic(path: String, context: PublicReadContext): String? = requestScoped(path, context)
 internal suspend fun requestScoped(path: String, context: PublicReadContext, body: String? = null, key: String? = null, method: String? = null): String? {
  if(!isCurrentPublicRead(context)) return null
  try {
   val body = api.request(path, body = body, token = context.token, responseLimit = 33_554_432, idempotencyKey = key, method = method)
   return body.takeIf { isCurrentPublicRead(context) }
  } catch(e: RemoteFailure) {
   if(e.error == AuthError.Unauthorized) rejectUnauthorized(context)
   throw e
  }
 }

 internal suspend fun rejectUnauthorized(context: PublicReadContext) {
  if(context.token == null) return
  lock.withLock {
   if(isCurrentPublicRead(context)) {
    epoch.incrementAndGet(); clearMemory(); publish(SessionState.Guest)
    try { withContext(NonCancellable) { tokens.clear() } }
    catch(_: IOException) { publish(SessionState.Recovery(AuthError.StorageUnavailable)) }
   }
  }
 }

 override suspend fun refreshSession() {
  val captured = lock.withLock {
   if(!initialized) {
    try { token = tokens.read(); initialized = true } catch(e: IOException) { publish(SessionState.Recovery(AuthError.StorageUnavailable)); return }
   }
   val t = token ?: run { publish(SessionState.Guest); return }
   epoch.get() to t
  }
  try {
   val dto = api.json.decodeFromString<UserDto>(api.request("me", token = captured.second))
   require(dto.id.isNotBlank() && dto.email.isNotBlank())
   lock.withLock {
    if(epoch.get() != captured.first || token !== captured.second) return
    user = dto; profiles.value = ProfileResult.Ready(dto.profile())
    val id = (mutable.value.state as? SessionState.Authenticated)?.session?.id ?: UUID.randomUUID().toString()
    publish(SessionState.Authenticated(Session(id, dto.account())))
   }
  } catch(e: RemoteFailure) {
   lock.withLock { if(epoch.get() == captured.first && token === captured.second) {
    if(e.error == AuthError.Unauthorized) { epoch.incrementAndGet(); clearMemory(); publish(SessionState.Guest); try { withContext(NonCancellable) { tokens.clear() } } catch(e: IOException) { publish(SessionState.Recovery(AuthError.StorageUnavailable)) } }
    else if(mutable.value.state is SessionState.Authenticated && user!=null &&
        (e.error in setOf(AuthError.Network,AuthError.Timeout,AuthError.ServerUnavailable) || e.error is AuthError.Cooldown)) {
     // Warm validated identity may edit its own local drafts. DB contents never establish a cold session.
    } else publish(SessionState.Recovery(e.error))
   } }
  } catch(e: SerializationException) { recovery(captured.first, AuthError.Protocol) }
    catch(e: IllegalArgumentException) { recovery(captured.first, AuthError.Protocol) }
    catch(e: IOException) { recovery(captured.first, AuthError.StorageUnavailable) }
 }
 private suspend fun recovery(generation: Long, error: AuthError) = lock.withLock { if(epoch.get() == generation) publish(SessionState.Recovery(error)) }
 private suspend fun authenticate(body: String, path: String): AuthResult<Session> {
  val generation = epoch.incrementAndGet()
  return try {
   val dto = api.json.decodeFromString<SessionDto>(api.request(path, body))
   if(dto.token.isBlank() || dto.user.id.isBlank()) return AuthResult.Failed(AuthError.Protocol)
   val session = Session(UUID.randomUUID().toString(), dto.user.account())
   lock.withLock {
    currentCoroutineContext().ensureActive()
    if(epoch.get() != generation) return@withLock AuthResult.Failed(AuthError.SessionChanged)
    try { tokens.write(AuthSecret(dto.token)); currentCoroutineContext().ensureActive() }
    catch(e: CancellationException) { withContext(NonCancellable) { clearMemory(); tokens.clear(); publish(SessionState.Guest) }; throw e }
    catch(e: IOException) { withContext(NonCancellable) { clearMemory(); tokens.clear(); publish(SessionState.Guest) }; return@withLock AuthResult.Failed(AuthError.StorageUnavailable) }
    if(epoch.get() != generation) { withContext(NonCancellable) { clearMemory(); tokens.clear(); publish(SessionState.Guest) }; return@withLock AuthResult.Failed(AuthError.SessionChanged) }
    token = AuthSecret(dto.token); initialized = true; user = dto.user
    profiles.value = ProfileResult.Ready(dto.user.profile()); publish(SessionState.Authenticated(session))
    AuthResult.Success(session)
   }
  } catch(e: RemoteFailure) { AuthResult.Failed(e.error) }
    catch(e: SerializationException) { AuthResult.Failed(AuthError.Protocol) }
    catch(e: IOException) { AuthResult.Failed(AuthError.StorageUnavailable) }
 }
 override suspend fun signUp(name: String, email: String, password: AuthSecret, consentAccepted: Boolean) = authenticate(api.json.encodeToString(Signup(name,email,password.value,consentAccepted)), "auth/register")
 override suspend fun signIn(email: String, password: AuthSecret) = authenticate(api.json.encodeToString(Credentials(email,password.value)), "auth/login")
 override suspend fun signOut() {
  epoch.incrementAndGet() // Revoke in-flight acceptance before waiting on an unrelated store write.
  val old = withContext(NonCancellable) { lock.withLock { epoch.incrementAndGet(); val old=token to user?.account()?.id; clearMemory(); publish(SessionState.Guest); try { tokens.clear() } catch(e: IOException) { publish(SessionState.Recovery(AuthError.StorageUnavailable)) } finally { initialized = true }; old } }
  old.second?.let { account -> withContext(NonCancellable) {
   try { onExplicitLogout(account) } catch(e:CancellationException){throw e} catch(e:Exception){lock.withLock {publish(SessionState.Recovery(AuthError.StorageUnavailable))}}
  } }
  if(old.first != null) try { api.request("auth/logout", "{}", old.first) } catch(_: RemoteFailure) { /* Local logout is authoritative locally, remote revocation is best effort. */ }
 }
 private suspend fun dispatch(path: String, email: String): AuthResult<EmailDispatch> = try { api.request(path, api.json.encodeToString(EmailBody(email))); AuthResult.Success(EmailDispatch()) } catch(e: RemoteFailure) { AuthResult.Failed(e.error) }
 override suspend fun requestVerification(email: String) = dispatch("auth/resend-verification", email)
 override suspend fun requestPasswordReset(email: String) = dispatch("auth/forgot-password", email)
 override suspend fun completeEmailLink(token: AuthSecret, password: AuthSecret?): AuthResult<Unit> = try { api.request("auth/email-link", api.json.encodeToString(EmailLinkBody(token.value,password?.value))); AuthResult.Success(Unit) } catch(e: RemoteFailure) { AuthResult.Failed(e.error) }
 override suspend fun consentDocuments(): AuthResult<ConsentDocuments> = try { val d=api.json.decodeFromString<ConsentDto>(api.request("auth/consent")); AuthResult.Success(ConsentDocuments(ConsentDocument(d.terms.url,d.terms.version),ConsentDocument(d.privacy.url,d.privacy.version))) } catch(e: RemoteFailure) { AuthResult.Failed(e.error) } catch(e: SerializationException) { AuthResult.Failed(AuthError.Protocol) }
 val profileRepository: ProfileRepository = object : ProfileRepository {
  override fun observeProfile(): Flow<ProfileResult> = profiles.asStateFlow()
  override suspend fun refreshProfile(): ProfileResult { refreshSession(); return profiles.value }
  override suspend fun saveProfile(fields: ProfileFields): ProfileResult = ProfileResult.Failed(ProfileError.FEATURE_UNAVAILABLE)
 }
}

/** Data-local request scope; toString never reveals credential or contacts. */
internal class PublicReadContext(val snapshot: SessionSnapshot, val epoch: Long,
 internal val token: AuthSecret?, val profile: Profile?) {
 override fun toString() = "PublicReadContext(generation=${snapshot.generation}, credentials=[redacted])"
}
