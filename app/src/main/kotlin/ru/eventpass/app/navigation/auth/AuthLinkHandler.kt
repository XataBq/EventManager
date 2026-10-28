package ru.eventpass.app.navigation.auth

import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.platform.AuthDemoLink
import ru.eventpass.core.platform.AuthDemoLinkParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Activity delivery is deferred until H1; this boundary never persists the URI/token. */
class AuthLinkHandler(private val auth: AuthRepository) {
    private val handled = LinkedHashSet<String>()
    private val inFlight = mutableSetOf<String>()
    private val lock = Any()
    suspend fun handle(requestId: String, value: String?): AuthLinkResult {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (requestId in handled || !inFlight.add(requestId)) return AuthLinkResult.Duplicate
        }
        try {
            val result = when (val link = AuthDemoLinkParser.parse(value)) {
                null -> AuthLinkResult.Invalid
                is AuthDemoLink.Event -> AuthLinkResult.Event(ReturnDestination.Event(link.eventId))
                is AuthDemoLink.Confirm -> when (val confirmation = auth.confirmEmail(link.challengeId, AuthSecret(link.token))) {
                    is AuthResult.Failed -> AuthLinkResult.Failed(confirmation.error)
                    is AuthResult.Success -> AuthLinkResult.EmailConfirmed(confirmation.value)
                }
            }
            currentCoroutineContext().ensureActive()
            if (result !is AuthLinkResult.Failed) synchronized(lock) {
                handled.add(requestId)
                if (handled.size > 64) handled.remove(handled.first())
            }
            return result
        } finally {
            // Explicit retry is allowed after any repository failure, exception or cancellation.
            synchronized(lock) { inFlight.remove(requestId) }
        }
    }
}
sealed interface AuthLinkResult {
    data object Duplicate : AuthLinkResult
    data object Invalid : AuthLinkResult
    data class Event(val destination: ReturnDestination.Event) : AuthLinkResult
    data class EmailConfirmed(val email: String) : AuthLinkResult
    data class Failed(val error: AuthError) : AuthLinkResult
}
