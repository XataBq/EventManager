package ru.eventpass.app.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.app.navigation.auth.*
import ru.eventpass.core.domain.auth.*
import ru.eventpass.data.fake.product.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    private fun navigation() = NavigationState(mutableStateOf(RootTab.Profile), RootTab.entries.associateWith {
        NavBackStack<NavKey>(it, DemoDetails("old-private"), GalleryKey)
    })
    private fun environment(hook: suspend (String) -> Unit = {}) = FakeEventPassEnvironment(
        authPolicy = FakeAuthPolicy(passwordIterations = 1_000), beforeAuthOperation = hook,
    )
    private val password get() = AuthSecret("DemoPass123!")

    @Test fun unknownAndGuestClearEveryTabAndRestoredScopeCannotAuthorize() = runTest {
        val e = environment(); val nav = navigation()
        val c = SessionCoordinator(e.auth, backgroundScope, nav::clearPrivateState)
        assertEquals(SessionGate.Loading, c.gate.value)
        RootTab.entries.forEach { assertEquals(listOf(it), nav.stacks.getValue(it).toList()) }
        assertFalse(AuthNavigationPolicy.canRestorePrivate(e.auth.currentSession(), PrivateScope("old", "alex")))
        e.auth.observeSession().first(); runCurrent()
        assertEquals(SessionGate.Login, c.gate.value)
        assertEquals(AuthResult.Success(Unit), c.signIn("alex@example.com", password)); runCurrent()
        val scope = (c.gate.value as SessionGate.Shell).scope
        assertTrue(AuthNavigationPolicy.canRestorePrivate(e.auth.currentSession(), scope))
        nav.select(RootTab.Events); nav.openGallery(); nav.select(RootTab.Profile); nav.openGallery()
        c.signOut(); runCurrent()
        assertEquals(SessionGate.Login, c.gate.value)
        assertFalse(AuthNavigationPolicy.canRestorePrivate(e.auth.currentSession(), scope))
        RootTab.entries.forEach { assertEquals(listOf(it), nav.stacks.getValue(it).toList()) }
        nav.back(); nav.select(RootTab.Events)
        assertEquals(listOf(RootTab.Events), nav.currentStack.toList())
    }

    @Test fun safeEventReturnIsConsumedOnceAndOrdinaryLoginUsesTickets() = runTest {
        val e = environment(); e.auth.observeSession().first()
        val target = ReturnDestination.Event("alex-owned")
        val restored = Json.decodeFromString<ReturnDestination>(Json.encodeToString<ReturnDestination>(target))
        val c = SessionCoordinator(e.auth, backgroundScope, {}, restored)
        c.signIn("alex@example.com", password); runCurrent()
        val first = c.gate.value as SessionGate.Shell
        assertEquals(target, first.destination); assertNull(c.pendingForSavedState())
        c.refreshSession(); runCurrent(); assertEquals(first, c.gate.value)
        c.signOut(); c.signIn("alex@example.com", password); runCurrent()
        assertEquals(ReturnDestination.Root(RootTab.Tickets), (c.gate.value as SessionGate.Shell).destination)
        assertNotEquals(first.scope, (c.gate.value as SessionGate.Shell).scope)
        assertFalse(AuthNavigationPolicy.canRestorePrivate(e.auth.currentSession(), first.scope))
    }

    @Test fun concurrentLoginIsBusyAndLateReplyAfterLogoutCannotOpenShell() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val e = environment { if (it == "signIn") { entered.complete(Unit); release.await() } }
        e.auth.observeSession().first()
        val c = SessionCoordinator(e.auth, backgroundScope, {})
        val request = async { c.signIn("alex@example.com", password) }
        entered.await()
        assertEquals(AuthResult.Failed(AuthError.Busy), c.signIn("host@example.com", password))
        c.signOut(); release.complete(Unit)
        assertEquals(AuthResult.Failed(AuthError.SessionChanged), request.await()); runCurrent()
        assertEquals(SessionGate.Login, c.gate.value)
    }

    @Test fun expiryAndAccountChangeClearPrivateScopes() = runTest {
        val time = object : Clock() {
            var now = FakeFixtures.NOW
            override fun instant(): Instant = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val e = FakeEventPassEnvironment(time, authPolicy = FakeAuthPolicy(passwordIterations = 1_000))
        e.auth.observeSession().first()
        var clears = 0
        val c = SessionCoordinator(e.auth, backgroundScope, { clears++ })
        c.requireLogin(ReturnDestination.Root(RootTab.Events))
        c.signIn("alex@example.com", password); runCurrent()
        val alex = c.gate.value as SessionGate.Shell
        assertEquals(ReturnDestination.Root(RootTab.Events), alex.destination)
        c.signIn("host@example.com", password); runCurrent()
        assertNotEquals(alex.scope, (c.gate.value as SessionGate.Shell).scope)
        val before = clears; time.now = time.now.plusSeconds(1800)
        c.refreshSession(); runCurrent()
        assertTrue(clears > before); assertEquals(SessionGate.Login, c.gate.value)
    }

    @Test fun forbiddenRemovesRouteWithoutLoginLoop() {
        val target = ReturnDestination.Event("alex-owned")
        assertEquals(GuardDecision.RemoveForbidden, AuthNavigationPolicy.guard(AccessOutcome.Forbidden, target))
        assertEquals(GuardDecision.Login(target), AuthNavigationPolicy.guard(AccessOutcome.Unauthenticated, target))
        assertEquals(GuardDecision.Allow, AuthNavigationPolicy.guard(AccessOutcome.Allowed, target))
    }

    @Test fun confirmationLinkIsOneTimeAndNeverCreatesSession() = runTest {
        val e = environment()
        val created = e.auth.signUp("李", "new@example.com", AuthSecret("FreshPass123!")) as AuthResult.Success
        val mail = e.scenarios.authOutbox().last()
        val handler = AuthLinkHandler(e.auth)
        val uri = "eventpass-demo://demo/confirm/${created.value.id}?token=${mail.secret.value}"
        assertEquals(AuthLinkResult.EmailConfirmed("new@example.com"), handler.handle("cold-request", uri))
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        assertEquals(AuthLinkResult.Duplicate, handler.handle("cold-request", uri))
        assertEquals(AuthLinkResult.Failed(AuthError.ConsumedChallenge), handler.handle("warm-request", uri))
        assertEquals(AuthLinkResult.Event(ReturnDestination.Event("open-registration")), handler.handle("event", "eventpass-demo://demo/e/open-registration"))
    }
}
