package ru.eventpass.core.domain.auth

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.Account

/** Never serialize a password, confirmation token, reset code or grant into UI saved state. */
class AuthSecret(val value: String) {
    override fun toString() = "[redacted]"
}

/** id is a local non-secret scope; expiresAt is present only when actually known (demo). */
data class Session(val id: String, val account: Account, val expiresAt: Instant? = null)
sealed interface SessionState {
    data object Unknown : SessionState
    data object Guest : SessionState
    data class Recovery(val error: AuthError) : SessionState
    data class Authenticated(val session: Session) : SessionState
}
data class SessionSnapshot(val generation: Long, val state: SessionState)

sealed interface AuthError {
    data object InvalidInput : AuthError
    data object InvalidCredentials : AuthError
    data object EmailTaken : AuthError
    data object EmailUnverified : AuthError
    data object InvalidChallenge : AuthError
    data object ExpiredChallenge : AuthError
    data object ConsumedChallenge : AuthError
    data object AlreadyVerified : AuthError
    data class Cooldown(val retryAt: Instant) : AuthError
    data object Network : AuthError
    data object Timeout : AuthError
    data object StorageUnavailable : AuthError
    data object SessionChanged : AuthError
    data object Busy : AuthError
    data object Unauthorized : AuthError
    data object Forbidden : AuthError
    data object Protocol : AuthError
    data object ServerUnavailable : AuthError
    data object FeatureUnavailable : AuthError
}
sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failed(val error: AuthError) : AuthResult<Nothing>
}
data class EmailChallenge(val id: String, val email: String, val expiresAt: Instant, val resendAt: Instant)
data class EmailDispatch(val resendAt: Instant? = null)
data class ConsentDocument(val url: String?, val version: String?)
data class ConsentDocuments(val terms: ConsentDocument, val privacy: ConsentDocument)

interface AuthRepository {
    fun observeSession(): Flow<SessionSnapshot>
    /** Same local session source as observeSession; saved routes do not prove authorization. */
    fun currentSession(): SessionSnapshot
    suspend fun refreshSession()
    suspend fun signUp(name: String, email: String, password: AuthSecret, consentAccepted: Boolean): AuthResult<Session>
    suspend fun signIn(email: String, password: AuthSecret): AuthResult<Session>
    suspend fun signOut()
    suspend fun requestVerification(email: String): AuthResult<EmailDispatch>
    suspend fun requestPasswordReset(email: String): AuthResult<EmailDispatch>
    suspend fun completeEmailLink(token: AuthSecret, password: AuthSecret? = null): AuthResult<Unit>
    suspend fun consentDocuments(): AuthResult<ConsentDocuments>
}

/** Temporary demo rules, not a backend password contract. */
data class AuthInputPolicy(val minPasswordLength: Int = 8, val maxPasswordLength: Int = 256) {
    init { require(minPasswordLength > 0 && maxPasswordLength >= minPasswordLength) }
    fun validPassword(value: AuthSecret) = value.value.length in minPasswordLength..maxPasswordLength &&
        value.value.none(Char::isISOControl)
    fun validEmail(value: String) = value.length <= 254 &&
        value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
    fun validLoginPassword(value: AuthSecret) = value.value.length in 1..maxPasswordLength
    fun validName(value: String) = value.trim().length in 1..300 && value.none(Char::isISOControl)
}
