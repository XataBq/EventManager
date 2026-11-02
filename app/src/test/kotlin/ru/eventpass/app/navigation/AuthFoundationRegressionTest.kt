package ru.eventpass.app.navigation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.test.TestScope
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
        @Volatile var beforeWrite: () -> Unit = {}
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        override fun read() = bytes?.clone()
        override fun write(snapshot: ByteArray) {
            beforeWrite()
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
        fun drainInto(executor: Executor) {
            while (true) executor.execute(tasks.poll() ?: return)
        }
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

    @Test fun sessionAcceptanceDoesNotBlockCallerQueueBehindSnapshotWrite() = runTest {
        acceptanceUnderContention(cancelLogin = false)
    }

    @Test fun cancellingAcceptanceWaitKeepsCallerQueueResponsiveAndSessionGuest() = runTest {
        acceptanceUnderContention(cancelLogin = true)
    }

    private suspend fun TestScope.acceptanceUnderContention(cancelLogin: Boolean) {
        val disk = BlockingStorage(); val e = environment(disk)
        e.auth.observeSession().first()
        val c = SessionCoordinator(e.auth, backgroundScope, {}); runCurrent()
        val dispatcher = PausingDispatcher()
        val caller = CoroutineScope(SupervisorJob() + dispatcher)
        val uiThread = AtomicReference<Thread>()
        val ui = Executors.newSingleThreadExecutor { task -> Thread(task, "auth-ui-contract").also(uiThread::set) }
        val loginWriteEntered = CountDownLatch(1)
        val releaseLoginWrite = CountDownLatch(1)
        disk.beforeWrite = { loginWriteEntered.countDown(); check(releaseLoginWrite.await(10, TimeUnit.SECONDS)) }
        val login = caller.async { e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")) }
        var writer: kotlinx.coroutines.Deferred<AuthResult<EmailChallenge>>? = null
        try {
            dispatcher.next().run()
            // Ensure the IO return suspends before releasing it; a cached/fast reply can complete inline.
            check(loginWriteEntered.await(10, TimeUnit.SECONDS))
            disk.beforeWrite = {}; releaseLoginWrite.countDown()
            val resumeLogin = dispatcher.next() // Login IO is complete; acceptance has not run.
            disk.blockNext = true
            writer = async { e.auth.signUp("Other", "contender@example.com", AuthSecret("NewDemoPass123!")) }
            disk.entered.await() // A different auth operation holds the shared lock in write.
            val marker = CountDownLatch(1)
            ui.execute(resumeLogin)
            ui.execute { marker.countDown() }
            val responsive = marker.await(2, TimeUnit.SECONDS)
            assertTrue("Caller queue blocked behind disk IO; state=${uiThread.get().state}", responsive)
            assertFalse("Acceptance must wait for the writer", login.isCompleted)
            assertEquals(SessionState.Guest, e.auth.currentSession().state)
            if (cancelLogin) {
                login.cancel()
                ui.execute(dispatcher.next())
                login.join() // Cancellation completes while the writer is still held.
                assertTrue(login.isCancelled)
            }
            disk.release.countDown()
            assertTrue(writer.await() is AuthResult.Success)
            if (!cancelLogin) {
                ui.execute(dispatcher.next())
                assertTrue(login.await() is AuthResult.Success)
            }
            runCurrent()
            assertTrue(e.scenarios.accounts().any { it.email == "contender@example.com" })
            if (cancelLogin) {
                assertEquals(SessionState.Guest, e.auth.currentSession().state)
                assertEquals(SessionGate.Login, c.gate.value)
            } else {
                assertTrue(e.auth.currentSession().state is SessionState.Authenticated)
                assertTrue(c.gate.value is SessionGate.Shell)
            }
        } finally {
            releaseLoginWrite.countDown()
            disk.release.countDown()
            writer?.cancelAndJoin()
            caller.cancel()
            dispatcher.drainInto(ui)
            ui.shutdown()
            check(ui.awaitTermination(5, TimeUnit.SECONDS)) { "Test UI executor did not stop" }
        }
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
