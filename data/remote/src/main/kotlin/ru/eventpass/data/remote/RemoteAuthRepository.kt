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
import kotlinx.serialization.json.*
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.profile.*

class RemoteAuthRepository(private val api: AuthHttp, private val tokens: SessionTokenStore,
 private val onExplicitLogout:suspend (ru.eventpass.core.domain.event.UserId)->Unit = {}) : AuthRepository {
 private val lock = Mutex()
 private val accountOperations=Mutex()
 private val profileVersion=AtomicLong()
 private var pendingDeletion=false
 private val privateCleanup=java.util.concurrent.CopyOnWriteArrayList<()->Unit>()
 internal fun onPrivateSessionClosed(action:()->Unit) {privateCleanup.add(action)}
 private val mutable = MutableStateFlow(SessionSnapshot(0, SessionState.Unknown))
 private var token: AuthSecret? = null
 private var initialized = false
 private val epoch = AtomicLong()
 private var user: UserDto? = null
 private val profiles = MutableStateFlow<ProfileResult>(ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED))
 override fun currentSession() = mutable.value
 override fun observeSession(): Flow<SessionSnapshot> = mutable.asStateFlow()
 private fun publish(state: SessionState) { mutable.value = SessionSnapshot(epoch.get(), state) }
 private fun clearMemory() { token = null; user = null; pendingDeletion=false; profiles.value = ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED); privateCleanup.forEach {runCatching(it)} }
 /** Captures only an already validated session. Guest/recovery never reads a stored bearer. */
 internal suspend fun capturePublicRead(): PublicReadContext = lock.withLock {
  PublicReadContext(mutable.value, epoch.get(), if(mutable.value.state is SessionState.Authenticated) token else null,
   if(mutable.value.state is SessionState.Authenticated) user?.profile() else null)
 }
 internal fun isCurrentPublicRead(context: PublicReadContext): Boolean =
  mutable.value == context.snapshot && epoch.get() == context.epoch && (context.token == null || token === context.token)
 internal suspend fun requestPublic(path: String, context: PublicReadContext): String? = requestScoped(path, context)
 internal suspend fun requestScoped(path: String, context: PublicReadContext, body: String? = null, key: String? = null, method: String? = null,expectedStatus:Int?=null): String? {
  if(!isCurrentPublicRead(context)) return null
  try {
   val body = api.request(path, body = body, token = context.token, responseLimit = 33_554_432, idempotencyKey = key, method = method,expectedStatus=expectedStatus)
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
   Triple(epoch.get(),t,profileVersion.get())
  }
  try {
   val dto = api.json.decodeFromString<UserDto>(api.request("me", token = captured.second))
   require(dto.id.isNotBlank() && dto.email.isNotBlank())
   lock.withLock {
    if(epoch.get() != captured.first || token !== captured.second || profileVersion.get()!=captured.third) return
    user = dto; profiles.value = ProfileResult.Ready(dto.profile())
    val id = (mutable.value.state as? SessionState.Authenticated)?.session?.id ?: UUID.randomUUID().toString()
    publish(SessionState.Authenticated(Session(id, dto.account())))
   }
  } catch(e: RemoteFailure) {
   lock.withLock { if(epoch.get() == captured.first && token === captured.second && profileVersion.get()==captured.third) {
    if(e.error == AuthError.Unauthorized) { epoch.incrementAndGet(); clearMemory(); publish(SessionState.Guest); try { withContext(NonCancellable) { tokens.clear() } } catch(e: IOException) { publish(SessionState.Recovery(AuthError.StorageUnavailable)) } }
    else if(mutable.value.state is SessionState.Authenticated && user!=null &&
        (e.error in setOf(AuthError.Network,AuthError.Timeout,AuthError.ServerUnavailable) || e.error is AuthError.Cooldown)) {
     // Warm validated identity may edit its own local drafts. DB contents never establish a cold session.
    } else publish(SessionState.Recovery(e.error))
   } }
  } catch(e: SerializationException) { recovery(captured.first, AuthError.Protocol,captured.third) }
    catch(e: IllegalArgumentException) { recovery(captured.first, AuthError.Protocol,captured.third) }
    catch(e: IOException) { recovery(captured.first, AuthError.StorageUnavailable,captured.third) }
 }
 private suspend fun recovery(generation: Long, error: AuthError,version:Long=profileVersion.get()) = lock.withLock { if(epoch.get() == generation && profileVersion.get()==version) publish(SessionState.Recovery(error)) }
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
 private data class Retired(val token:AuthSecret?,val account:ru.eventpass.core.domain.event.UserId?,val epoch:Long,var error:ProfileError?=null)
 private fun sameActor(c:PublicReadContext):Boolean {
  val current=(mutable.value.state as? SessionState.Authenticated)?.session
  val old=(c.snapshot.state as? SessionState.Authenticated)?.session
  return epoch.get()==c.epoch && token===c.token && current!=null && old!=null && current.id==old.id && current.account.id==old.account.id
 }
 /** One logout/delete cleanup path. IO account purge remains outside the auth lock. */
 private suspend fun retire(expected:PublicReadContext?=null):Retired? = withContext(NonCancellable) {
  if(expected==null) epoch.incrementAndGet()
  val retired=lock.withLock {
   if(expected!=null && !sameActor(expected)) return@withLock null
   val next=Retired(token,user?.account()?.id,epoch.incrementAndGet())
   profileVersion.incrementAndGet();clearMemory();publish(SessionState.Guest);initialized=true
   try {tokens.clear()} catch(_:IOException) {next.error=ProfileError.STORAGE_UNAVAILABLE;publish(SessionState.Recovery(AuthError.StorageUnavailable))}
   next
  } ?: return@withContext null
  retired.account?.let {account->
   try {onExplicitLogout(account)} catch(_:Exception) {
    retired.error=ProfileError.STORAGE_UNAVAILABLE
    lock.withLock {if(epoch.get()==retired.epoch) publish(SessionState.Recovery(AuthError.StorageUnavailable))}
   }
  }
  retired
 }
 override suspend fun signOut() {
  val retired=retire() ?: return
  retired.token?.let {try {api.request("auth/logout","{}",it)} catch(_:RemoteFailure){}}
 }
 private suspend fun dispatch(path: String, email: String): AuthResult<EmailDispatch> = try { api.request(path, api.json.encodeToString(EmailBody(email))); AuthResult.Success(EmailDispatch()) } catch(e: RemoteFailure) { AuthResult.Failed(e.error) }
 override suspend fun requestVerification(email: String) = dispatch("auth/resend-verification", email)
 override suspend fun requestPasswordReset(email: String) = dispatch("auth/forgot-password", email)
 override suspend fun completeEmailLink(token: AuthSecret, password: AuthSecret?): AuthResult<Unit> = try { api.request("auth/email-link", api.json.encodeToString(EmailLinkBody(token.value,password?.value))); AuthResult.Success(Unit) } catch(e: RemoteFailure) { AuthResult.Failed(e.error) }
 override suspend fun consentDocuments(): AuthResult<ConsentDocuments> = try { val d=api.json.decodeFromString<ConsentDto>(api.request("auth/consent")); AuthResult.Success(ConsentDocuments(ConsentDocument(d.terms.url,d.terms.version),ConsentDocument(d.privacy.url,d.privacy.version))) } catch(e: RemoteFailure) { AuthResult.Failed(e.error) } catch(e: SerializationException) { AuthResult.Failed(AuthError.Protocol) }
 private fun profileError(ex:Exception):ProfileError=if(ex !is RemoteFailure) ProfileError.PROTOCOL else when(ex.error) {
  AuthError.Network->ProfileError.NETWORK;AuthError.Timeout->ProfileError.TIMEOUT;AuthError.Unauthorized->ProfileError.AUTHENTICATION_REQUIRED
  AuthError.Forbidden->ProfileError.FORBIDDEN;AuthError.SessionChanged->ProfileError.SESSION_CHANGED;AuthError.InvalidInput->ProfileError.INVALID_INPUT
  AuthError.EmailTaken->ProfileError.EMAIL_TAKEN;AuthError.ServerUnavailable->ProfileError.SERVER_UNAVAILABLE
  is AuthError.Cooldown->ProfileError.RATE_LIMITED;else->ProfileError.PROTOCOL
 }
 private fun validated(dto:UserDto,c:PublicReadContext):Profile {
  require(dto.id==c.profile?.account?.id?.value && dto.publicId.isNotBlank())
  return dto.profile().also {require(ProfilePolicy.valid(it.fields))}
 }
 private suspend fun acceptUser(dto:UserDto,c:PublicReadContext,version:Long):ProfileResult=lock.withLock {
  if(!sameActor(c)) return@withLock ProfileResult.Failed(ProfileError.SESSION_CHANGED)
  if(profileVersion.get()!=version) return@withLock user?.let {ProfileResult.Ready(it.profile())} ?: ProfileResult.Failed(ProfileError.SESSION_CHANGED)
  val profile=validated(dto,c)
  val session=(mutable.value.state as SessionState.Authenticated).session
  user=dto
  // Same sessionId/epoch/accountId: email edits do not destroy private owners or purge Room drafts.
  publish(SessionState.Authenticated(session.copy(account=dto.account())))
  profiles.value=ProfileResult.Ready(profile)
  profiles.value
 }
 private suspend fun readProfile():ProfileResult {
  val c=capturePublicRead();val version=profileVersion.get()
  if(c.token==null) return ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED)
  return try {
   val body=requestScoped("me",c) ?: return if(sameActor(c)) user?.let {ProfileResult.Ready(it.profile())} ?: ProfileResult.Failed(ProfileError.SESSION_CHANGED) else ProfileResult.Failed(ProfileError.SESSION_CHANGED)
   acceptUser(api.json.decodeFromString<UserDto>(body),c,version)
  } catch(ex:CancellationException){throw ex} catch(ex:Exception) {
   if(!sameActor(c)) return ProfileResult.Failed(ProfileError.SESSION_CHANGED)
   if(profileVersion.get()!=version) return user?.let {ProfileResult.Ready(it.profile())} ?: ProfileResult.Failed(ProfileError.SESSION_CHANGED)
   ProfileResult.Failed(profileError(ex))
  }
 }
 val profileRepository: ProfileRepository = object : ProfileRepository {
  override fun observeProfile():Flow<ProfileResult> = combine(mutable,profiles) {snapshot,result->snapshot to result}.transform { (snapshot,result)->
   if(snapshot!=mutable.value) return@transform
   val account=(snapshot.state as? SessionState.Authenticated)?.session?.account
   if(account==null) emit(ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED))
   else if(result is ProfileResult.Failed) emit(result)
   else if(result is ProfileResult.Ready && result.profile.account==account) emit(result.copy(deletionUncertain=pendingDeletion))
  }
  override suspend fun refreshProfile()=readProfile()
  override suspend fun saveProfile(fields:ProfileFields):ProfileResult {
   if(!accountOperations.tryLock()) return ProfileResult.Failed(ProfileError.BUSY)
   try {
    val c=capturePublicRead();val baseline=c.profile ?: return ProfileResult.Failed(ProfileError.AUTHENTICATION_REQUIRED)
    if(pendingDeletion) return ProfileResult.Failed(ProfileError.DELETE_UNCONFIRMED)
    val desired=ProfilePolicy.normalized(fields.copy(email=fields.email ?: baseline.account.email))
    if(!ProfilePolicy.valid(desired)) return ProfileResult.Failed(ProfileError.INVALID_INPUT)
    val old=baseline.fields
    val patch=buildJsonObject {
     if(desired.name!=old.name) put("fullName",desired.name)
     if(desired.email!=baseline.account.email) put("email",desired.email!!)
     if(desired.phone!=old.phone) put("phone",desired.phone?.let(::JsonPrimitive) ?: JsonNull)
     if(desired.organization!=old.organization) put("organization",desired.organization?.let(::JsonPrimitive) ?: JsonNull)
     if(desired.emailNotifications!=old.emailNotifications) put("notificationsEnabled",desired.emailNotifications)
    }
    if(patch.isEmpty()) return ProfileResult.Ready(baseline)
    val version=profileVersion.incrementAndGet()
    val body=requestScoped("me",c,patch.toString(),method="PATCH") ?: return ProfileResult.Failed(ProfileError.SESSION_CHANGED)
    return acceptUser(api.json.decodeFromString<UserDto>(body),c,version)
   } catch(ex:CancellationException){throw ex} catch(ex:Exception){return ProfileResult.Failed(profileError(ex))}
   finally {accountOperations.unlock()}
  }
  override suspend fun deleteAccount():DeleteAccountResult {
   if(!accountOperations.tryLock()) return DeleteAccountResult.Failed(ProfileError.BUSY)
   try {
    val c=capturePublicRead()
    if(c.token==null) return DeleteAccountResult.AccessRevoked
    if(pendingDeletion) return DeleteAccountResult.Uncertain(ProfileError.DELETE_UNCONFIRMED)
    profileVersion.incrementAndGet()
    try {
     val result=requestScoped("me",c,method="DELETE",expectedStatus=204) ?: return DeleteAccountResult.Failed(ProfileError.SESSION_CHANGED)
     require(result.isBlank())
     return withContext(NonCancellable) {
      val retired=retire(c)
      if(retired==null) {
       // Confirmed deletion of old actor never logs a different current actor out.
       c.profile?.account?.id?.let {onExplicitLogout(it)}
       DeleteAccountResult.Failed(ProfileError.SESSION_CHANGED)
      } else DeleteAccountResult.Deleted(retired.error)
     }
    } catch(ex:CancellationException) {pendingDeletion=true;throw ex}
      catch(ex:Exception) {
       val error=profileError(ex)
       if(error==ProfileError.AUTHENTICATION_REQUIRED) return DeleteAccountResult.AccessRevoked
       if(error in setOf(ProfileError.NETWORK,ProfileError.TIMEOUT,ProfileError.SERVER_UNAVAILABLE,ProfileError.PROTOCOL)) {pendingDeletion=true;return DeleteAccountResult.Uncertain(error)}
       return DeleteAccountResult.Failed(error)
      }
   } finally {accountOperations.unlock()}
  }
  override suspend fun reconcileDeletion():DeleteAccountResult {
   if(!accountOperations.tryLock()) return DeleteAccountResult.Failed(ProfileError.BUSY)
   try {
    val c=capturePublicRead();if(c.token==null) return DeleteAccountResult.AccessRevoked
    return try {
     val version=profileVersion.incrementAndGet()
     val body=requestScoped("me",c) ?: return DeleteAccountResult.Failed(ProfileError.SESSION_CHANGED)
     when(val profile=acceptUser(api.json.decodeFromString<UserDto>(body),c,version)) {
      is ProfileResult.Ready->{pendingDeletion=false;DeleteAccountResult.Present(profile.profile)}
      is ProfileResult.Failed->DeleteAccountResult.Failed(profile.error)
     }
    } catch(ex:CancellationException){throw ex} catch(ex:Exception){
     val error=profileError(ex)
     if(error==ProfileError.AUTHENTICATION_REQUIRED) DeleteAccountResult.AccessRevoked else DeleteAccountResult.Uncertain(error)
    }
   } finally {accountOperations.unlock()}
  }
 }

}

/** Data-local request scope; toString never reveals credential or contacts. */
internal class PublicReadContext(val snapshot: SessionSnapshot, val epoch: Long,
 internal val token: AuthSecret?, val profile: Profile?) {
 override fun toString() = "PublicReadContext(generation=${snapshot.generation}, credentials=[redacted])"
}
