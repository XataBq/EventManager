package ru.eventpass.data.remote

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable internal data class Credentials(val email: String, val password: String)
@Serializable internal data class Signup(val fullName: String, val email: String, val password: String, val consentAccepted: Boolean)
@Serializable internal data class EmailBody(val email: String)
@Serializable internal data class EmailLinkBody(val token: String, val password: String? = null)
@Serializable internal data class UserDto(val id: String, val publicId: String, val fullName: String, val email: String, val phone: String?, val organization: String?, val emailVerified: Boolean, val notificationsEnabled: Boolean) {
 fun account() = Account(UserId(id), email, emailVerified)
 fun profile() = Profile(account(), ProfileFields(fullName, phone, organization, notificationsEnabled))
}
@Serializable internal data class SessionDto(val token: String, val user: UserDto)
@Serializable internal data class ErrorDto(val code: String, val fields: Map<String,String> = emptyMap())
@Serializable internal data class ConsentDto(val terms: ConsentItemDto, val privacy: ConsentItemDto)
@Serializable internal data class ConsentItemDto(val url: String?, val version: String?)
internal class RemoteFailure(val error: AuthError, val httpStatus: Int? = null, val machineCode: String? = null, val fields: Map<String, String> = emptyMap()) : IOException("Remote request failed")

/** Bounded, cancellable HTTP. No logging, redirects or mutation retries; bearer only API origin. */
class AuthHttp(baseUrl: String, client: OkHttpClient = OkHttpClient()) {
 internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
 private val base = baseUrl.toHttpUrlStrict()
 private val http = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
 init { require(base.encodedPath.endsWith("/")); require(base.scheme == "https" || base.host in setOf("localhost", "127.0.0.1", "::1")) }
 internal suspend fun request(path: String, body: String? = null, token: AuthSecret? = null, method: String? = null, responseLimit: Int = 1_048_576, idempotencyKey: String? = null): String = withContext(Dispatchers.IO) {
  require(responseLimit in 1..33_554_432)
  require(!path.startsWith("/") && !path.contains(":") && path.split('/').none { it == "." || it == ".." })
  val target = requireNotNull(base.resolve(path))
  require(target.scheme == base.scheme && target.host == base.host && target.port == base.port)
  require(target.encodedPath.startsWith(base.encodedPath))
  val builder = Request.Builder().url(target).header("Accept", "application/json")
  if (idempotencyKey != null) { require(idempotencyKey.matches(Regex("[A-Za-z0-9._:-]{16,128}"))); builder.header("Idempotency-Key", idempotencyKey) }
  if (token != null) builder.header("Authorization", "Bearer " + token.value)
  if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
  if(method != null) builder.method(method, null)
  try {
   suspendCancellableCoroutine<String> { c ->
    val call = http.newCall(builder.build()); c.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
     override fun onFailure(call: Call, e: IOException) { if(c.isActive) c.resumeWithException(e) }
     override fun onResponse(call: Call, response: Response) {
      try { val text = decodeResponse(response, responseLimit); if(c.isActive) c.resume(text) }
      catch(e: Exception) { if(c.isActive) c.resumeWithException(e) }
     }
    })
   }
  } catch(e: SocketTimeoutException) { throw RemoteFailure(AuthError.Timeout) }
    catch(e: RemoteFailure) { throw e }
    catch(e: IOException) { throw RemoteFailure(AuthError.Network) }
 }
 internal suspend fun downloadPdf(path: String, token: AuthSecret?): ByteArray = withContext(Dispatchers.IO) {
  require(!path.startsWith("/") && !path.contains(":") && path.split('/').none { it == "." || it == ".." })
  val target = requireNotNull(base.resolve(path))
  require(target.scheme == base.scheme && target.host == base.host && target.port == base.port && target.encodedPath.startsWith(base.encodedPath))
  val builder = Request.Builder().url(target).header("Accept", "application/pdf")
  token?.let { builder.header("Authorization", "Bearer " + it.value) }
  try {
   suspendCancellableCoroutine<ByteArray> { c ->
    val call = http.newCall(builder.build()); c.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
     override fun onFailure(call: Call, e: IOException) { if(c.isActive) c.resumeWithException(e) }
     override fun onResponse(call: Call, response: Response) {
      try {
       val bytes = response.use {
        if(!it.isSuccessful) { decodeResponse(it, 1_048_576); throw RemoteFailure(AuthError.Protocol) }
        if(it.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "application/pdf") throw RemoteFailure(AuthError.Protocol)
        val source = it.body?.source() ?: throw RemoteFailure(AuthError.Protocol)
        source.request(16_777_217); val raw = source.buffer.readByteArray()
        if(raw.size > 16_777_216 || raw.size < 5 || raw.copyOfRange(0,5).toString(Charsets.US_ASCII) != "%PDF-") throw RemoteFailure(AuthError.Protocol)
        raw
       }
       if(c.isActive) c.resume(bytes)
      } catch(e: Exception) { if(c.isActive) c.resumeWithException(e) }
     }
    })
   }
  } catch(e: SocketTimeoutException) { throw RemoteFailure(AuthError.Timeout) }
    catch(e: RemoteFailure) { throw e }
    catch(e: IOException) { throw RemoteFailure(AuthError.Network) }
 }
 private fun decodeResponse(response: Response, limit: Int): String = response.use {
  val source = it.body?.source(); source?.request(limit.toLong() + 1)
  val raw = source?.buffer?.readByteArray() ?: byteArrayOf()
  if(raw.size > limit) throw RemoteFailure(AuthError.Protocol)
  val text = raw.toString(Charsets.UTF_8)
  if(!it.isSuccessful) {
   val envelope = runCatching { json.decodeFromString<ErrorDto>(text) }.getOrNull()
   val code = envelope?.code
   throw RemoteFailure(when {
    it.code == 429 -> AuthError.Cooldown(Instant.now().plusSeconds(it.header("Retry-After")?.toLongOrNull()?.coerceIn(1,86400) ?: 60))
    code == "INVALID_CREDENTIALS" -> AuthError.InvalidCredentials
    code == "EMAIL_TAKEN" -> AuthError.EmailTaken
    it.code == 401 -> AuthError.Unauthorized
    it.code == 403 -> AuthError.Forbidden
    it.code == 422 -> AuthError.InvalidInput
    it.code >= 500 -> AuthError.ServerUnavailable
    else -> AuthError.Protocol
   }, it.code, code, envelope?.fields.orEmpty())
  }
  if(it.code != 204 && !it.header("Content-Type").orEmpty().contains("application/json")) throw RemoteFailure(AuthError.Protocol)
  text
 }
 private fun String.toHttpUrlStrict() = toHttpUrl()
}
