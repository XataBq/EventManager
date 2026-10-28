package ru.eventpass.core.domain.auth

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.Account

/** Never serialize a password, confirmation token, reset code or grant into UI saved state. */
class AuthSecret(val value: String) {
    override fun toString() = "[redacted]"
}

data class Session(val id: String, val account: Account, val expiresAt: Instant)
sealed interface SessionState {
    data object Unknown : SessionState
    data object Guest : SessionState
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
}
sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failed(val error: AuthError) : AuthResult<Nothing>
}
data class EmailChallenge(val id: String, val email: String, val expiresAt: Instant, val resendAt: Instant)

interface AuthRepository {
    fun observeSession(): Flow<SessionSnapshot>
    /** Same local session source as observeSession; saved routes do not prove authorization. */
    fun currentSession(): SessionSnapshot
    suspend fun refreshSession()
    suspend fun signUp(name: String, email: String, password: AuthSecret): AuthResult<EmailChallenge>
    suspend fun signIn(email: String, password: AuthSecret): AuthResult<Session>
    suspend fun signOut()
    suspend fun requestVerification(email: String): AuthResult<EmailChallenge>
    suspend fun confirmEmail(challengeId: String, token: AuthSecret): AuthResult<String>
    suspend fun requestPasswordResetCode(email: String): AuthResult<EmailChallenge>
    suspend fun verifyResetCode(challengeId: String, code: AuthSecret): AuthResult<AuthSecret>
    suspend fun resetPassword(grant: AuthSecret, password: AuthSecret): AuthResult<Unit>
}

/** Temporary demo rules, not a backend password contract. */
data class AuthInputPolicy(val minPasswordLength: Int = 8, val maxPasswordLength: Int = 128) {
    init { require(minPasswordLength > 0 && maxPasswordLength >= minPasswordLength) }
    fun validPassword(value: AuthSecret) = value.value.length in minPasswordLength..maxPasswordLength &&
        value.value.none(Char::isISOControl)
    fun validEmail(value: String) = value.length <= 254 &&
        value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
    fun validName(value: String) = value.trim().length in 1..200 && value.none(Char::isISOControl)
}
