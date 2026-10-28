package ru.eventpass.app.navigation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.app.navigation.auth.*
import ru.eventpass.core.domain.auth.*
import ru.eventpass.data.fake.product.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthFoundationRegressionTest {
    private class BlockingStorage : FakeAuthStorage {
        @Volatile var bytes: ByteArray? = null
        @Volatile var blockNext = false
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        override fun read() = bytes?.clone()
        override fun write(snapshot: ByteArray) {
            if (blockNext) {
                blockNext = false
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release snapshot write" }
            }
            bytes = snapshot.clone()
        }
    }
    private class PausingDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.put(block) }
        fun next(): Runnable = requireNotNull(tasks.poll(10, TimeUnit.SECONDS)) { "Expected a queued continuation" }
    }
    private fun environment(storage: FakeAuthStorage = VolatileAuthStorage, hook: suspend (String) -> Unit = {}) =
        FakeEventPassEnvironment(authStorage = storage, authPolicy = FakeAuthPolicy(passwordIterations = 1_000), beforeAuthOperation = hook)

    @Test fun cancelledLoginDuringWriteNeverPublishesAuthenticatedOrShell() = runTest {
        val disk = BlockingStorage(); val e = environment(disk)
        e.auth.observeSession().first()
        val observed = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.auth.observeSession().collect { observed += it.state } }
        val c = SessionCoordinator(e.auth, backgroundScope, {})
        runCurrent()
        disk.blockNext = true
        val login = async { c.signIn("alex@example.com", AuthSecret("DemoPass123!")) }
        try {
            disk.entered.await()
            login.cancel()
        } finally { disk.release.countDown() }
        login.join(); runCurrent()
        assertTrue(login.isCancelled)
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        assertEquals(SessionGate.Login, c.gate.value)
        assertTrue(observed.none { it is SessionState.Authenticated })
        assertEquals(AuthResult.Success(Unit), c.signIn("alex@example.com", AuthSecret("DemoPass123!")))
    }

    @Test fun cancellationBeforeCallerResumesCannotPublishPreparedLogin() = runTest {
        val e = environment(); e.auth.observeSession().first()
        val c = SessionCoordinator(e.auth, backgroundScope, {}); runCurrent()
        val dispatcher = PausingDispatcher()
        val caller = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val login = caller.async { e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")) }
            dispatcher.next().run() // Start signIn and dispatch its IO work.
            val resume = dispatcher.next() // IO finished; do not resume the caller yet.
            login.cancel()
            resume.run(); login.join(); runCurrent()
            assertTrue(login.isCancelled)
            assertEquals(SessionState.Guest, e.auth.currentSession().state)
            assertEquals(SessionGate.Login, c.gate.value)
        } finally { caller.cancel() }
    }

    @Test fun cancellationDuringDurableSignupKeepsMemoryAndSnapshotConsistent() = runTest {
        val disk = BlockingStorage(); val e = environment(disk)
        e.auth.observeSession().first(); disk.blockNext = true
        val signup = async { e.auth.signUp("Name", "cancelled@example.com", AuthSecret("NewDemoPass123!")) }
        try { disk.entered.await(); signup.cancel() }
        finally { disk.release.countDown() }
        signup.join()
        assertTrue(signup.isCancelled)
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        assertTrue(e.scenarios.accounts().any { it.email == "cancelled@example.com" })
        assertEquals(1, e.scenarios.authOutbox().size)
        val cold = environment(disk)
        assertEquals(AuthResult.Failed(AuthError.EmailUnverified), cold.auth.signIn("cancelled@example.com", AuthSecret("NewDemoPass123!")))
    }

    @Test fun logoutBeforeCallerAcceptsPreparedLoginKeepsGuest() = runTest {
        val e = environment(); e.auth.observeSession().first()
        val dispatcher = PausingDispatcher()
        val caller = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val login = caller.async { e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")) }
            dispatcher.next().run()
            val resume = dispatcher.next()
            e.auth.signOut()
            resume.run()
            assertEquals(AuthResult.Failed(AuthError.SessionChanged), login.await())
            assertEquals(SessionState.Guest, e.auth.currentSession().state)
        } finally { caller.cancel() }
    }

    @Test fun temporaryConfirmationFailuresAllowSameDeliveryRetryAndSuccessIsDeduplicated() = runTest {
        for (error in listOf(AuthError.Network, AuthError.Timeout, AuthError.StorageUnavailable)) {
            var attempts = 0
            val e = environment(hook = { if (it == "confirmEmail") attempts++ })
            e.auth.signUp("Name", "retry@example.com", AuthSecret("NewDemoPass123!"))
            val mail = e.scenarios.authOutbox().last()
            val uri = "eventpass-demo://demo/confirm/${mail.challenge.id}?token=${mail.secret.value}"
            val handler = AuthLinkHandler(e.auth)
            e.scenarios.failNextAuth(error)
            assertEquals(AuthLinkResult.Failed(error), handler.handle("delivery-1", uri))
            assertEquals(1, attempts) // No automatic retry.
            assertEquals(AuthLinkResult.EmailConfirmed("retry@example.com"), handler.handle("delivery-1", uri))
            assertEquals(AuthLinkResult.Duplicate, handler.handle("delivery-1", uri))
            assertEquals(2, attempts)
            assertEquals(SessionState.Guest, e.auth.currentSession().state)
        }
    }

    @Test fun inFlightDuplicateDoesNotMutateAndCancelledDeliveryCanBeRetried() = runTest {
        val entered = CompletableDeferred<Unit>()
        var first = true; var attempts = 0
        val e = environment(hook = { if (it == "confirmEmail") {
            attempts++
            if (first) { first = false; entered.complete(Unit); awaitCancellation() }
        } })
        e.auth.signUp("Name", "retry@example.com", AuthSecret("NewDemoPass123!"))
        val mail = e.scenarios.authOutbox().last()
        val uri = "eventpass-demo://demo/confirm/${mail.challenge.id}?token=${mail.secret.value}"
        val handler = AuthLinkHandler(e.auth)
        val request = async { handler.handle("delivery-1", uri) }
        entered.await()
        assertEquals(AuthLinkResult.Duplicate, handler.handle("delivery-1", uri))
        assertEquals(1, attempts)
        request.cancelAndJoin()
        assertEquals(AuthLinkResult.EmailConfirmed("retry@example.com"), handler.handle("delivery-1", uri))
        assertEquals(AuthLinkResult.Duplicate, handler.handle("delivery-1", uri))
        assertEquals(2, attempts)
    }
}
