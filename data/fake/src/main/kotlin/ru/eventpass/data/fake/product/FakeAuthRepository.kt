package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.UserId

internal class FakeAuthRepository(
    private val state: MutableStateFlow<FakeProductState>,
    private val lock: Any,
    private val clock: Clock,
    private val storage: FakeAuthStorage,
    private val policy: FakeAuthPolicy,
    private val secrets: FakeAuthSecrets,
    private val beforeOperation: suspend (String) -> Unit,
) : AuthRepository {
    private val initialization = Mutex()
    @Volatile private var initialized = false

    private suspend fun initialize() = initialization.withLock {
        if (!initialized) withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (initialized) return@synchronized
                try {
                    val bytes = storage.read()
                    val current = state.value
                    val restored = bytes?.let(AuthSnapshotCodec::decode)
                    val accounts = restored?.first ?: current.accounts
                    val data = restored?.second?.copy(generation = current.auth.generation, nextError = current.auth.nextError) ?: current.auth.copy(passwords = current.accounts.mapValues {
                        passwordProof(AuthSecret("DemoPass123!"), policy.passwordIterations)
                    })
                    state.value = current.copy(accounts = accounts, auth = data.copy(session = null))
                } catch (_: IOException) {
                    state.value = state.value.copy(auth = AuthData(healthy = false))
                } catch (_: IllegalArgumentException) {
                    state.value = state.value.copy(auth = AuthData(healthy = false))
                } catch (_: java.time.DateTimeException) {
                    state.value = state.value.copy(auth = AuthData(healthy = false))
                }
                initialized = true
            }
        }
    }

    override fun observeSession(): Flow<SessionSnapshot> = flow {
        initialize()
        emitAll(state.map(::snapshot).distinctUntilChanged())
    }
    override fun currentSession(): SessionSnapshot = if (initialized) snapshot(state.value)
        else SessionSnapshot(state.value.auth.generation, SessionState.Unknown)

    private fun snapshot(s: FakeProductState): SessionSnapshot {
        val session = s.auth.session?.takeIf { clock.instant() < it.expiresAt }
        return SessionSnapshot(s.auth.generation, when {
            !s.auth.healthy -> SessionState.Unknown
            session == null -> SessionState.Guest
            else -> SessionState.Authenticated(session.copy(account = s.accounts.getValue(session.account.id)))
        })
    }

    override suspend fun refreshSession() {
        initialize()
        withContext(Dispatchers.IO) { synchronized(lock) {
            val s = state.value
            if (s.auth.session?.let { clock.instant() >= it.expiresAt } == true) {
                state.value = s.copy(auth = s.auth.copy(session = null, generation = s.auth.generation + 1))
            }
        } }
    }

    override suspend fun signOut() {
        // Invalidate in-flight operations even if logout races initial snapshot restoration.
        initialize()
        withContext(Dispatchers.IO) { synchronized(lock) {
            val s = state.value
            state.value = s.copy(auth = s.auth.copy(session = null, generation = s.auth.generation + 1))
        } }
    }

    private suspend fun <T> command(name: String, action: (FakeProductState) -> Pair<FakeProductState, AuthResult<T>>): AuthResult<T> {
        initialize()
        val generation = state.value.auth.generation
        beforeOperation(name)
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            synchronized(lock) {
                context.ensureActive()
                val s = state.value
                when {
                    !s.auth.healthy -> AuthResult.Failed(AuthError.StorageUnavailable)
                    s.auth.generation != generation -> AuthResult.Failed(AuthError.SessionChanged)
                    s.auth.nextError != null -> {
                        state.value = s.copy(auth = s.auth.copy(nextError = null))
                        AuthResult.Failed(s.auth.nextError)
                    }
                    else -> {
                        val (next, result) = action(s)
                        if (next != s) {
                            try { storage.write(AuthSnapshotCodec.encode(next.accounts, next.auth)) }
                            catch (_: IOException) { return@synchronized AuthResult.Failed(AuthError.StorageUnavailable) }
                            state.value = next
                        }
                        result
                    }
                }
            }
        }
    }
    private fun <T> failed(s: FakeProductState, error: AuthError): Pair<FakeProductState, AuthResult<T>> = s to AuthResult.Failed(error)

    override suspend fun signUp(name: String, email: String, password: AuthSecret): AuthResult<EmailChallenge> = command("signUp") { s ->
        val mail = email.trim()
        when {
            !policy.input.validName(name) || !policy.input.validEmail(mail) || !policy.input.validPassword(password) -> failed(s, AuthError.InvalidInput)
            s.accounts.values.any { it.email == mail } -> failed(s, AuthError.EmailTaken)
            else -> {
                val account = Account(UserId(secrets.id()), mail, false)
                val next = s.copy(accounts = s.accounts + (account.id to account), auth = s.auth.copy(
                    passwords = s.auth.passwords + (account.id to passwordProof(password, policy.passwordIterations)),
                    names = s.auth.names + (account.id to name.trim()),
                ))
                issue(next, account, false)
            }
        }
    }

    override suspend fun signIn(email: String, password: AuthSecret): AuthResult<Session> = command("signIn") { s ->
        val account = s.accounts.values.singleOrNull { it.email == email.trim() }
        when {
            account == null || s.auth.passwords[account.id]?.matches(password) != true -> failed(s, AuthError.InvalidCredentials)
            !account.emailVerified -> failed(s, AuthError.EmailUnverified)
            else -> {
                val session = Session(secrets.id(), account, clock.instant().plus(policy.sessionLifetime))
                s.copy(auth = s.auth.copy(session = session, generation = s.auth.generation + 1)) to AuthResult.Success(session)
            }
        }
    }

    override suspend fun requestVerification(email: String) = request(email, false)
    override suspend fun requestPasswordResetCode(email: String) = request(email, true)
    private suspend fun request(email: String, reset: Boolean): AuthResult<EmailChallenge> = command("requestChallenge") { s ->
        val account = s.accounts.values.singleOrNull { it.email == email.trim() }
        val previous = s.auth.challenges.values.singleOrNull { it.userId == account?.id && it.reset == reset }
        when {
            account == null -> failed(s, AuthError.InvalidInput)
            !reset && account.emailVerified -> failed(s, AuthError.AlreadyVerified)
            previous != null && clock.instant() < previous.resendAt -> failed(s, AuthError.Cooldown(previous.resendAt))
            else -> issue(s, account, reset)
        }
    }

    private fun issue(s: FakeProductState, account: Account, reset: Boolean): Pair<FakeProductState, AuthResult<EmailChallenge>> {
        val token = AuthSecret(if (reset) secrets.resetCode() else secrets.token())
        val challenge = EmailChallenge(secrets.id(), account.email, clock.instant().plus(policy.challengeLifetime), clock.instant().plus(policy.resendCooldown))
        val stored = StoredChallenge(challenge.id, account.id, reset, digest(token), challenge.expiresAt, challenge.resendAt)
        val data = s.auth.copy(challenges = s.auth.challenges.filterValues { it.userId != account.id || it.reset != reset } + (challenge.id to stored),
            mail = s.auth.mail.takeLast(63) + FakeAuthMail(challenge, reset, token))
        return s.copy(auth = data) to AuthResult.Success(challenge)
    }

    private fun challengeError(c: StoredChallenge?, token: AuthSecret, reset: Boolean): AuthError? = when {
        c == null || c.reset != reset || c.hash != digest(token) -> AuthError.InvalidChallenge
        c.consumed -> AuthError.ConsumedChallenge
        clock.instant() >= c.expiresAt -> AuthError.ExpiredChallenge
        else -> null
    }
    override suspend fun confirmEmail(challengeId: String, token: AuthSecret): AuthResult<String> = command("confirmEmail") { s ->
        val c = s.auth.challenges[challengeId]
        val error = challengeError(c, token, false)
        if (error != null) failed(s, error) else {
            val challenge = requireNotNull(c)
            val account = s.accounts.getValue(challenge.userId)
            s.copy(accounts = s.accounts + (account.id to account.copy(emailVerified = true)), auth = s.auth.copy(
                challenges = s.auth.challenges + (challenge.id to challenge.copy(consumed = true)),
            )) to AuthResult.Success(account.email)
        }
    }

    override suspend fun verifyResetCode(challengeId: String, code: AuthSecret): AuthResult<AuthSecret> = command("verifyResetCode") { s ->
        val c = s.auth.challenges[challengeId]
        val error = challengeError(c, code, true)
        if (error != null) failed(s, error) else {
            val challenge = requireNotNull(c)
            val grant = AuthSecret(secrets.token())
            s.copy(auth = s.auth.copy(challenges = s.auth.challenges +
                (challenge.id to challenge.copy(consumed = true, grantHash = digest(grant))))) to AuthResult.Success(grant)
        }
    }
    override suspend fun resetPassword(grant: AuthSecret, password: AuthSecret): AuthResult<Unit> = command("resetPassword") { s ->
        val c = s.auth.challenges.values.singleOrNull { it.reset && it.grantHash == digest(grant) }
        when {
            c == null -> failed(s, AuthError.InvalidChallenge)
            c.resetUsed -> failed(s, AuthError.ConsumedChallenge)
            clock.instant() >= c.expiresAt -> failed(s, AuthError.ExpiredChallenge)
            !policy.input.validPassword(password) -> failed(s, AuthError.InvalidInput)
            else -> s.copy(auth = s.auth.copy(passwords = s.auth.passwords + (c.userId to passwordProof(password, policy.passwordIterations)),
                challenges = s.auth.challenges + (c.id to c.copy(resetUsed = true)),
                session = null, generation = s.auth.generation + 1)) to AuthResult.Success(Unit)
        }
    }

    fun reset(seed: FakeProductState) = synchronized(lock) {
        val data = seed.auth.copy(passwords = seed.accounts.mapValues {
            passwordProof(AuthSecret("DemoPass123!"), policy.passwordIterations)
        }, generation = state.value.auth.generation + 1)
        storage.write(AuthSnapshotCodec.encode(seed.accounts, data))
        state.value = seed.copy(auth = data)
        initialized = true
    }
}
