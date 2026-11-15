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
    private val lock: Mutex,
    private val clock: Clock,
    private val storage: FakeAuthStorage,
    private val policy: FakeAuthPolicy,
    private val secrets: FakeAuthSecrets,
    private val beforeOperation: suspend (String) -> Unit,
) : AuthRepository {
    private val initialization = Mutex()
    @Volatile private var initialized = false
    @Volatile private var incompatibleLegacy: ByteArray? = null
    internal fun requiresLegacyReset() = incompatibleLegacy != null

    internal suspend fun initialize() = initialization.withLock {
        if (!initialized) withContext(Dispatchers.IO) {
            lock.withLock stateLock@ {
                if (initialized) return@stateLock
                try {
                    val bytes = storage.read()
                    val current = state.value
                    val restored = bytes?.let { ProductSnapshotCodec.restore(it, current) }
                    val accounts = restored?.accounts ?: current.accounts
                    val data = restored?.auth?.copy(generation = current.auth.generation, nextError = current.auth.nextError) ?: current.auth.copy(passwords = current.accounts.mapValues {
                        passwordProof(AuthSecret("DemoPass123!"), policy.passwordIterations)
                    })
                    state.value = (restored ?: current).copy(accounts = accounts, auth = data.copy(session = null))
                } catch (e: LegacyProductIncompatible) {
                    incompatibleLegacy = e.original.copyOf()
                    state.value = state.value.copy(auth = AuthData(healthy = false))
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
        val session = s.auth.session?.takeIf { it.expiresAt?.let { end -> clock.instant() < end } != false }
        return SessionSnapshot(s.auth.generation, when {
            !s.auth.healthy -> SessionState.Unknown
            session == null -> SessionState.Guest
            else -> SessionState.Authenticated(session.copy(account = s.accounts.getValue(session.account.id)))
        })
    }

    override suspend fun refreshSession() {
        initialize()
        withContext(Dispatchers.IO) { lock.withLock {
            val s = state.value
            if (s.auth.session?.let { it.expiresAt?.let { end -> clock.instant() >= end } == true } == true) {
                state.value = s.copy(auth = s.auth.copy(session = null, generation = s.auth.generation + 1))
            }
        } }
    }

    override suspend fun signOut() {
        // Invalidate in-flight operations even if logout races initial snapshot restoration.
        initialize()
        withContext(Dispatchers.IO) { lock.withLock {
            val s = state.value
            state.value = s.copy(auth = s.auth.copy(session = null, generation = s.auth.generation + 1))
        } }
    }

    private data class CompletedCommand<out T>(
        val result: AuthResult<T>, val sessionToAccept: Session? = null, val generation: Long = 0,
    )

    private suspend fun <T> command(
        name: String,
        acceptSessionOnCaller: Boolean = false,
        action: (FakeProductState) -> Pair<FakeProductState, AuthResult<T>>,
    ): AuthResult<T> {
        initialize()
        val generation = state.value.auth.generation
        beforeOperation(name)
        currentCoroutineContext().ensureActive()
        val completed: CompletedCommand<T> = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            lock.withLock {
                context.ensureActive()
                val s = state.value
                when {
                    !s.auth.healthy -> CompletedCommand(AuthResult.Failed(AuthError.StorageUnavailable))
                    s.auth.generation != generation -> CompletedCommand(AuthResult.Failed(AuthError.SessionChanged))
                    s.auth.nextError != null -> {
                        state.value = s.copy(auth = s.auth.copy(nextError = null))
                        CompletedCommand(AuthResult.Failed(s.auth.nextError))
                    }
                    else -> {
                        val (next, result) = action(s)
                        if (next != s) {
                            try { storage.write(ProductSnapshotCodec.encode(next)) }
                            catch (_: IOException) { return@withLock CompletedCommand(AuthResult.Failed(AuthError.StorageUnavailable)) }
                            if (acceptSessionOnCaller) {
                                // Account/proof changes are durable even if the caller cancels after disk commit.
                                // Session adoption is delayed until the caller resumes and is still current.
                                state.value = next.copy(auth = next.auth.copy(session = s.auth.session, generation = s.auth.generation))
                                context.ensureActive()
                                return@withLock CompletedCommand(result, requireNotNull(next.auth.session), s.auth.generation)
                            }
                            // Account/challenge changes have committed on disk, even if their caller cancelled.
                            state.value = next
                        }
                        CompletedCommand(result)
                    }
                }
            }
        }
        val context = currentCoroutineContext()
        context.ensureActive()
        val session = completed.sessionToAccept ?: return completed.result
        return lock.withLock {
            context.ensureActive()
            val current = state.value
            if (current.auth.generation != completed.generation || !current.auth.healthy ||
                current.accounts[session.account.id] != session.account || session.expiresAt?.let { clock.instant() >= it } == true
            ) AuthResult.Failed(AuthError.SessionChanged)
            else {
                // Acceptance point: active caller, no suspension/dispatcher hop after publication.
                state.value = current.copy(auth = current.auth.copy(session = session, generation = current.auth.generation + 1))
                completed.result
            }
        }
    }
    private fun <T> failed(s: FakeProductState, error: AuthError): Pair<FakeProductState, AuthResult<T>> = s to AuthResult.Failed(error)

    private fun normalizedEmail(value: String) = value.trim().lowercase(java.util.Locale.ROOT)

    /** A legacy collision has no safe winner: never merge accounts, proofs or their event links. */
    private fun matchingAccounts(s: FakeProductState, email: String): List<Account> {
        val normalized = normalizedEmail(email)
        return s.accounts.values.filter { normalizedEmail(it.email) == normalized }
    }

    override suspend fun signUp(name: String, email: String, password: AuthSecret, consentAccepted: Boolean): AuthResult<Session> = command("signUp", acceptSessionOnCaller = true) { s ->
        val mail = normalizedEmail(email)
        when {
            !consentAccepted || !policy.input.validName(name) || !policy.input.validEmail(mail) || !policy.input.validPassword(password) -> failed(s, AuthError.InvalidInput)
            matchingAccounts(s, mail).isNotEmpty() -> failed(s, AuthError.EmailTaken)
            else -> {
                val account = Account(UserId(secrets.id()), mail, false)
                val next = s.copy(accounts = s.accounts + (account.id to account), auth = s.auth.copy(
                    passwords = s.auth.passwords + (account.id to passwordProof(password, policy.passwordIterations)),
                    names = s.auth.names + (account.id to name.trim()),
                ))
                val issued = issue(next, account, false).first
                val session = Session(secrets.id(), account, clock.instant().plus(policy.sessionLifetime))
                issued.copy(auth = issued.auth.copy(session = session)) to AuthResult.Success(session)
            }
        }
    }

    override suspend fun signIn(email: String, password: AuthSecret): AuthResult<Session> = command("signIn", acceptSessionOnCaller = true) { s ->
        val account = matchingAccounts(s, email).singleOrNull()
        when {
            !policy.input.validLoginPassword(password) || account == null || s.auth.passwords[account.id]?.matches(password) != true -> failed(s, AuthError.InvalidCredentials)
            else -> {
                val session = Session(secrets.id(), account, clock.instant().plus(policy.sessionLifetime))
                s.copy(auth = s.auth.copy(session = session)) to AuthResult.Success(session)
            }
        }
    }

    override suspend fun requestVerification(email: String) = request(email, false)
    override suspend fun requestPasswordReset(email: String) = request(email, true)
    private suspend fun request(email: String, reset: Boolean): AuthResult<EmailDispatch> = command("requestChallenge") { s ->
        val mail = normalizedEmail(email)
        val account = matchingAccounts(s, mail).singleOrNull()
        val previous = s.auth.challenges.values.singleOrNull { it.userId == account?.id && it.reset == reset }
        when {
            !policy.input.validEmail(mail) -> failed(s, AuthError.InvalidInput)
            // Unknown or ambiguous legacy identity is accepted without dispatch, as with unknown email.
            account == null || (!reset && account.emailVerified) -> s to AuthResult.Success(EmailDispatch())
            previous != null && clock.instant() < previous.resendAt -> failed(s, AuthError.Cooldown(previous.resendAt))
            else -> issue(s, account, reset)
        }
    }

    private fun issue(s: FakeProductState, account: Account, reset: Boolean): Pair<FakeProductState, AuthResult<EmailDispatch>> {
        val token = AuthSecret(secrets.token())
        val challenge = EmailChallenge(secrets.id(), account.email, clock.instant().plus(policy.challengeLifetime), clock.instant().plus(policy.resendCooldown))
        val stored = StoredChallenge(challenge.id, account.id, reset, digest(token), challenge.expiresAt, challenge.resendAt)
        val data = s.auth.copy(challenges = s.auth.challenges.filterValues { it.userId != account.id || it.reset != reset } + (challenge.id to stored),
            mail = s.auth.mail.takeLast(63) + FakeAuthMail(challenge, reset, token))
        return s.copy(auth = data) to AuthResult.Success(EmailDispatch(challenge.resendAt))
    }

    override suspend fun completeEmailLink(token: AuthSecret, password: AuthSecret?): AuthResult<Unit> = command("completeEmailLink") { s ->
        val c = s.auth.challenges.values.singleOrNull { it.hash == digest(token) }
        when {
            c == null -> failed(s, AuthError.InvalidChallenge)
            c.consumed || c.resetUsed -> failed(s, AuthError.ConsumedChallenge)
            clock.instant() >= c.expiresAt -> failed(s, AuthError.ExpiredChallenge)
            c.reset && (password == null || !policy.input.validPassword(password)) -> failed(s, AuthError.InvalidInput)
            else -> {
                val account = s.accounts.getValue(c.userId)
                val challenges = s.auth.challenges.mapValues { (_, other) ->
                    if (other.userId == c.userId && (c.reset || !other.reset)) other.copy(consumed = true) else other
                }
                val data = s.auth.copy(challenges = challenges,
                    passwords = if (c.reset) s.auth.passwords + (c.userId to passwordProof(requireNotNull(password), policy.passwordIterations)) else s.auth.passwords,
                    session = if (c.reset && s.auth.session?.account?.id == c.userId) null else s.auth.session,
                    generation = s.auth.generation + if (c.reset) 1 else 0)
                s.copy(accounts = s.accounts + (account.id to account.copy(emailVerified = true)), auth = data) to AuthResult.Success(Unit)
            }
        }
    }

    override suspend fun consentDocuments(): AuthResult<ConsentDocuments> = command("consentDocuments") { s ->
        s to AuthResult.Success(ConsentDocuments(ConsentDocument(null, null), ConsentDocument(null, null)))
    }

    suspend fun reset(seed: FakeProductState) = withContext(Dispatchers.IO) {
        lock.withLock {
            val data = seed.auth.copy(passwords = seed.accounts.mapValues {
                passwordProof(AuthSecret("DemoPass123!"), policy.passwordIterations)
            }, generation = state.value.auth.generation + 1)
            incompatibleLegacy?.let { storage.backupLegacy(it.copyOf()) }
            storage.write(ProductSnapshotCodec.encode(seed.copy(auth = data)))
            incompatibleLegacy = null
            state.value = seed.copy(auth = data)
            initialized = true
        }
    }
}
