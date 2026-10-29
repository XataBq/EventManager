package ru.eventpass.app.navigation.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import ru.eventpass.core.domain.auth.*

@Serializable
sealed interface ReturnDestination {
    @Serializable data class Event(val eventId: String) : ReturnDestination {
        init { require(eventId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) }
    }
    @Serializable data class Root(val tab: ru.eventpass.app.navigation.RootTab) : ReturnDestination
}
data class PrivateScope(val sessionId: String, val userId: String)
sealed interface SessionGate {
    data object Loading : SessionGate
    data object Login : SessionGate
    data class Shell(val scope: PrivateScope, val destination: ReturnDestination) : SessionGate
}
enum class AccessOutcome { Allowed, Unauthenticated, Forbidden }
sealed interface GuardDecision {
    data object Allow : GuardDecision
    data class Login(val destination: ReturnDestination) : GuardDecision
    data object RemoveForbidden : GuardDecision
}
object AuthNavigationPolicy {
    fun guard(access: AccessOutcome, destination: ReturnDestination): GuardDecision = when (access) {
        AccessOutcome.Allowed -> GuardDecision.Allow
        AccessOutcome.Unauthenticated -> GuardDecision.Login(destination)
        AccessOutcome.Forbidden -> GuardDecision.RemoveForbidden
    }
    fun canRestorePrivate(snapshot: SessionSnapshot, saved: PrivateScope): Boolean =
        (snapshot.state as? SessionState.Authenticated)?.session?.let {
            it.id == saved.sessionId && it.account.id.value == saved.userId && it.account.emailVerified
        } == true
}

/** UI-dispatcher confined. The Activity ViewModel binds owner cleanup and observes this gate. */
class SessionCoordinator(
    private val auth: AuthRepository,
    scope: CoroutineScope,
    private val clearPrivateState: () -> Unit,
    pending: ReturnDestination? = null,
) {
    private val mutableGate = MutableStateFlow<SessionGate>(SessionGate.Loading)
    val gate = mutableGate.asStateFlow()
    private var pendingDestination = pending
    private var accepted: SessionSnapshot? = null
    private var operationVersion = 0L
    private val loginLock = Mutex()

    init {
        clearPrivateState() // Restored navigation is untrusted until the repository resolves session.
        scope.launch { auth.observeSession().collect { accept(it) } }
    }

    private fun accept(snapshot: SessionSnapshot) {
        if (snapshot != auth.currentSession() || snapshot == accepted) return
        val oldScope = (mutableGate.value as? SessionGate.Shell)?.scope
        val session = (snapshot.state as? SessionState.Authenticated)?.session
            ?.takeIf { it.account.emailVerified }
        val newScope = session?.let { PrivateScope(it.id, it.account.id.value) }
        if (oldScope != newScope || accepted?.generation != snapshot.generation) clearPrivateState()
        accepted = snapshot
        mutableGate.value = when {
            snapshot.state == SessionState.Unknown -> SessionGate.Loading
            session == null -> SessionGate.Login
            else -> SessionGate.Shell(requireNotNull(newScope), pendingDestination
                ?: ReturnDestination.Root(ru.eventpass.app.navigation.RootTab.Tickets)).also { pendingDestination = null }
        }
    }

    fun requireLogin(destination: ReturnDestination) { pendingDestination = destination }
    fun clearDestination() { pendingDestination = null }
    fun pendingForSavedState(): ReturnDestination? = pendingDestination

    suspend fun signIn(email: String, password: AuthSecret): AuthResult<Unit> {
        if (!loginLock.tryLock()) return AuthResult.Failed(AuthError.Busy)
        val version = operationVersion
        return try {
            val result = auth.signIn(email, password)
            if (version != operationVersion) AuthResult.Failed(AuthError.SessionChanged)
            else when (result) {
                is AuthResult.Failed -> result
                is AuthResult.Success -> {
                    val snapshot = auth.currentSession()
                    if ((snapshot.state as? SessionState.Authenticated)?.session?.id != result.value.id)
                        AuthResult.Failed(AuthError.SessionChanged)
                    else { accept(snapshot); AuthResult.Success(Unit) }
                }
            }
        } finally { loginLock.unlock() }
    }

    suspend fun signOut() {
        operationVersion++
        pendingDestination = null
        mutableGate.value = SessionGate.Loading
        clearPrivateState()
        auth.signOut()
        accept(auth.currentSession())
    }
    suspend fun refreshSession() { auth.refreshSession(); accept(auth.currentSession()) }
}
