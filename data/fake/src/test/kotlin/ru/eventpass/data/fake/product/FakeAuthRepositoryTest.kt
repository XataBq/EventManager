package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.registration.*

class FakeAuthRepositoryTest {
    private class Time(var now: Instant = FakeFixtures.NOW) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }
    private open class Disk : FakeAuthStorage {
        var bytes: ByteArray? = null
        var broken = false
        override fun read() = bytes?.clone()
        override fun write(snapshot: ByteArray) { if (broken) throw IOException("test disk failure"); bytes = snapshot.clone() }
    }
    private fun env(clock: Clock = Time(), disk: FakeAuthStorage = VolatileAuthStorage, hook: suspend (String) -> Unit = {}) =
        FakeEventPassEnvironment(clock, disk, FakeAuthPolicy(passwordIterations = 1_000), beforeAuthOperation = hook)
    private val password get() = AuthSecret("NewDemoPass1!")
    private fun <T> value(result: AuthResult<T>): T = (result as AuthResult.Success).value
    private fun failure(error: AuthError, result: AuthResult<*>) = assertEquals(AuthResult.Failed(error), result)
    private suspend fun create(e: FakeEventPassEnvironment, email: String = "new@example.com") = value(e.auth.signUp("Name", email, password, true))
    private suspend fun confirm(e: FakeEventPassEnvironment, email: String = "new@example.com") {
        value(e.auth.completeEmailLink(e.scenarios.authOutbox().last { !it.reset && it.challenge.email == email }.secret))
    }

    @Test fun signupCreatesUnverifiedSessionAndVerifyPreservesIt() = runTest {
        val e = env()
        assertEquals(SessionState.Unknown, e.auth.currentSession().state)
        assertEquals(SessionState.Guest, e.auth.observeSession().first().state)
        val session = create(e)
        assertFalse(session.account.emailVerified)
        assertEquals(session, (e.auth.currentSession().state as SessionState.Authenticated).session)
        failure(AuthError.EmailTaken, e.auth.signUp("Other", "new@example.com", password, true))
        failure(AuthError.InvalidCredentials, e.auth.signIn("new@example.com", AuthSecret("wrong")))
        val loggedIn = value(e.auth.signIn("new@example.com", password))
        assertFalse(loggedIn.account.emailVerified)
        confirm(e)
        val confirmed = (e.auth.currentSession().state as SessionState.Authenticated).session
        assertTrue(confirmed.account.emailVerified); assertEquals(loggedIn.id, confirmed.id)
        e.auth.signOut(); assertEquals(SessionState.Guest, e.auth.currentSession().state)
    }
    @Test fun consentFalseCannotCreateAccountOrSession() = runTest {
        val e = env()
        failure(AuthError.InvalidInput, e.auth.signUp("Name", "new@example.com", password, false))
        assertEquals(2, e.scenarios.accounts().size); assertTrue(e.scenarios.authOutbox().isEmpty())
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
    }
    @Test fun guestTicketsBecomeVisibleAfterSeparateSignupWithoutVerification() = runTest {
        val e = env()
        val ticket = (e.registrations.submitGuest(GuestRegistrationCommand(FakeFixtures.OPEN, 1, "Name", "new@example.com", consentAccepted = true)) as GuestRegistrationOutcome.Issued).ticket
        assertEquals(2, e.scenarios.accounts().size) // Guest autoaccount migration belongs to M03.
        create(e)
        assertEquals(listOf(ticket.ticketId), (e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.map { it.snapshot.ticketId })
        e.auth.signOut(); assertEquals(MyTicketsResult.AuthenticationRequired, e.tickets.observeMyTickets().first())
        create(e, "other@example.com")
        assertTrue((e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.isEmpty())
        assertEquals(ticket.ticketId, e.scenarios.outbox().single().ticketId)
    }
    @Test fun confirmationCooldownResendInvalidatesOldTokenAndConsumptionIsOneTime() = runTest {
        val time = Time(); val e = env(time); val c = create(e); val old = e.scenarios.authOutbox().last()
        failure(AuthError.Cooldown(old.challenge.resendAt), e.auth.requestVerification(c.account.email))
        time.advance(60); value(e.auth.requestVerification(c.account.email)); val mail = e.scenarios.authOutbox().last()
        failure(AuthError.InvalidChallenge, e.auth.completeEmailLink(old.secret))
        failure(AuthError.InvalidChallenge, e.auth.completeEmailLink(AuthSecret("bad")))
        value(e.auth.completeEmailLink(mail.secret)); failure(AuthError.ConsumedChallenge, e.auth.completeEmailLink(mail.secret))
        val count = e.scenarios.authOutbox().size
        value(e.auth.requestVerification(c.account.email)); assertEquals(count, e.scenarios.authOutbox().size)
    }
    @Test fun resetLinkConfirmsEmailInvalidatesSessionAndRequiresManualLogin() = runTest {
        val e = env(); create(e); value(e.auth.requestPasswordReset("new@example.com")); val mail = e.scenarios.authOutbox().last()
        assertFalse(mail.secret.value.matches(Regex("[0-9]{6}")))
        failure(AuthError.InvalidChallenge, e.auth.completeEmailLink(AuthSecret("wrong"), password))
        failure(AuthError.InvalidInput, e.auth.completeEmailLink(mail.secret))
        failure(AuthError.InvalidInput, e.auth.completeEmailLink(mail.secret, AuthSecret("short")))
        value(e.auth.completeEmailLink(mail.secret, AuthSecret("UpdatedPass1!")))
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        failure(AuthError.ConsumedChallenge, e.auth.completeEmailLink(mail.secret, password))
        failure(AuthError.InvalidCredentials, e.auth.signIn("new@example.com", password))
        assertTrue(value(e.auth.signIn("new@example.com", AuthSecret("UpdatedPass1!"))).account.emailVerified)
    }
    @Test fun resetDoesNotLogOutUnrelatedAccount() = runTest {
        val e = env(); create(e); val other = value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        value(e.auth.requestPasswordReset("new@example.com")); value(e.auth.completeEmailLink(e.scenarios.authOutbox().last().secret, password))
        assertEquals(other.id, (e.auth.currentSession().state as SessionState.Authenticated).session.id)
    }
    @Test fun verificationAndResetLinksExpireAt24Hours() = runTest {
        val time = Time(); val e = env(time); create(e); val verification = e.scenarios.authOutbox().last().secret
        value(e.auth.requestPasswordReset("new@example.com")); val reset = e.scenarios.authOutbox().last().secret
        time.advance(86_400)
        failure(AuthError.ExpiredChallenge, e.auth.completeEmailLink(verification))
        failure(AuthError.ExpiredChallenge, e.auth.completeEmailLink(reset, password))
    }
    @Test fun sessionExpiresAt30DaysAndRefreshPublishesGuest() = runTest {
        val time = Time(); val e = env(time); val first = value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        time.advance(30L * 86400 - 1); assertTrue(e.auth.currentSession().state is SessionState.Authenticated)
        time.advance(1); assertEquals(MyTicketsResult.AuthenticationRequired, e.tickets.observeMyTickets().first())
        e.auth.refreshSession(); assertEquals(SessionState.Guest, e.auth.observeSession().first().state)
        assertNotEquals(first.id, value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!"))).id)
    }
    @Test fun networkFailuresDoNotSendMailCreateAccountsOrLogin() = runTest {
        val e = env(); e.scenarios.failNextAuth(AuthError.Network)
        failure(AuthError.Network, e.auth.signUp("Name", "new@example.com", password, true))
        assertEquals(2, e.scenarios.accounts().size); assertTrue(e.scenarios.authOutbox().isEmpty())
        e.scenarios.failNextAuth(AuthError.Timeout); failure(AuthError.Timeout, e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
    }
    @Test fun cancellationAndLateLoginAfterLogoutDoNotMutateSession() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val e = env(hook = { if (it == "signIn") { entered.complete(Unit); release.await() } })
        val task = async { e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")) }
        entered.await(); e.auth.signOut(); release.complete(Unit); failure(AuthError.SessionChanged, task.await())
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        val cancelled = env(hook = { throw kotlinx.coroutines.CancellationException("test cancellation") })
        val job = async { cancelled.auth.signUp("Name", "x@example.com", password, true) }; job.join(); assertTrue(job.isCancelled)
        assertEquals(2, cancelled.scenarios.accounts().size)
    }
    @Test fun coldRestartRestoresAccountAndOneTimeTokenButNoSessionOrRawSecrets() = runTest {
        val disk = Disk(); val time = Time(); val e = env(time, disk); val c = create(e); val mail = e.scenarios.authOutbox().last()
        val text = disk.bytes!!.toString(Charsets.ISO_8859_1); assertFalse(text.contains(password.value)); assertFalse(text.contains(mail.secret.value))
        val cold = env(time, disk); assertEquals(SessionState.Guest, cold.auth.observeSession().first().state); assertTrue(cold.scenarios.authOutbox().isEmpty())
        value(cold.auth.completeEmailLink(mail.secret)); value(cold.auth.signIn(c.account.email, password))
        val again = env(time, disk); failure(AuthError.ConsumedChallenge, again.auth.completeEmailLink(mail.secret))
        value(again.auth.signIn(c.account.email, password)); again.scenarios.reset(); failure(AuthError.InvalidCredentials, again.auth.signIn(c.account.email, password))
    }
    @Test fun corruptedSnapshotFailsClosedAndDiskFailureDoesNotPublishSuccess() = runTest {
        val disk = Disk().apply { bytes = byteArrayOf(0,0,0,99) }; val e = env(disk = disk)
        assertEquals(SessionState.Unknown, e.auth.observeSession().first().state)
        failure(AuthError.StorageUnavailable, e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        e.scenarios.reset(); disk.broken = true; failure(AuthError.StorageUnavailable, e.auth.signUp("Name", "new@example.com", password, true))
        assertEquals(2, e.scenarios.accounts().size); assertTrue(e.scenarios.authOutbox().isEmpty())
    }
    @Test fun resentResetInvalidatesOldTokenAndColdResetPersistsNewPassword() = runTest {
        val time = Time(); val disk = Disk(); val e = env(time, disk)
        val dispatch = value(e.auth.requestPasswordReset("alex@example.com")); val old = e.scenarios.authOutbox().last().secret
        failure(AuthError.Cooldown(requireNotNull(dispatch.resendAt)), e.auth.requestPasswordReset("alex@example.com"))
        time.advance(60); value(e.auth.requestPasswordReset("alex@example.com")); val token = e.scenarios.authOutbox().last().secret
        failure(AuthError.InvalidChallenge, e.auth.completeEmailLink(old, password))
        val cold = env(time, disk); value(cold.auth.completeEmailLink(token, AuthSecret("UpdatedDemo123!")))
        assertEquals(SessionState.Guest, cold.auth.currentSession().state)
        val again = env(time, disk); failure(AuthError.InvalidCredentials, again.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        value(again.auth.signIn("alex@example.com", AuthSecret("UpdatedDemo123!"))); failure(AuthError.ConsumedChallenge, again.auth.completeEmailLink(token, password))
    }
    @Test fun authClockDoesNotChangeExistingEventClock() = runTest {
        val authTime = Time(FakeFixtures.NOW.plusSeconds(86400*5)); val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1000), authClock = authTime)
        value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        assertTrue((e.events.observePublicEvent(FakeFixtures.OPEN).first() as ru.eventpass.core.domain.event.PublicEventResult.Found).availability is ru.eventpass.core.domain.event.RegistrationAvailability.Open)
        authTime.advance(30L*86400); assertEquals(MyTicketsResult.AuthenticationRequired, e.tickets.observeMyTickets().first())
    }
    @Test fun persistedCooldownRetainsSubsecondDeadline() = runTest {
        val time = Time(FakeFixtures.NOW.plusNanos(987654321)); val disk = Disk(); val e = env(time,disk); val c = create(e)
        val resend = e.scenarios.authOutbox().last().challenge.resendAt
        failure(AuthError.Cooldown(resend), env(time,disk).auth.requestVerification(c.account.email))
    }
    @Test fun unknownEmailDispatchIsAcceptedWithoutRevealingExistenceOrSendingMail() = runTest {
        val e = env(); value(e.auth.requestPasswordReset("missing@example.com")); value(e.auth.requestVerification("missing@example.com"))
        assertTrue(e.scenarios.authOutbox().isEmpty()); assertEquals(2,e.scenarios.accounts().size)
    }
    @Test fun consentDocumentsDoNotInventMissingUrls() = runTest {
        val docs = value(env().auth.consentDocuments()); assertNull(docs.terms.url); assertNull(docs.privacy.url)
    }
    @Test fun signupCancelledDuringCommittedWritePreservesAccountWithoutAcceptingSession() = runTest {
        val disk = object : Disk() {
            var afterWrite: (() -> Unit)? = null
            override fun write(snapshot: ByteArray) { super.write(snapshot); afterWrite?.also { afterWrite = null }?.invoke() }
        }
        val e = env(disk = disk)
        val pending = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { e.auth.signUp("Committed", "committed@example.com", password, true) }
        disk.afterWrite = { pending.cancel() }
        pending.start(); pending.join(); assertTrue(pending.isCancelled)
        assertTrue(e.scenarios.accounts().any { it.email == "committed@example.com" })
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        val restarted = env(disk = disk)
        assertEquals(SessionState.Guest, restarted.auth.observeSession().first().state)
        value(restarted.auth.signIn("committed@example.com", password))
    }
    @Test fun legacyAuthKeepsVerificationButRetiresOldResetCodesAndGrants() = runTest {
        val now = FakeFixtures.NOW
        val account = FakeFixtures.accounts().associateBy { it.id }
        val verify = AuthSecret("legacy-verify-token"); val reset = AuthSecret("123456")
        val rows = listOf(
            StoredChallenge("v", FakeFixtures.ALEX, false, digest(verify), now.plusSeconds(900), now),
            StoredChallenge("r", FakeFixtures.ALEX, true, digest(reset), now.plusSeconds(900), now, grantHash = digest(AuthSecret("old-grant"))))
        val old = AuthData(passwords = account.mapValues { passwordProof(password, 1000) }, names = mapOf(FakeFixtures.ALEX to "Original"), challenges = rows.associateBy { it.id })
        val disk = Disk().apply { bytes = LegacyAuthV1Fixture.encode(account, old) }
        val e = env(disk = disk)
        value(e.auth.completeEmailLink(verify))
        failure(AuthError.InvalidChallenge, e.auth.completeEmailLink(reset, password))
        value(e.auth.signIn("alex@example.com", password))
        val restored = ProductSnapshotCodec.restore(requireNotNull(disk.bytes), FakeProductState(account, FakeFixtures.events().associateBy { it.id }, FakeFixtures.registrations(), FakeFixtures.tickets(FakeFixtures.events()), FakeFixtures.grants()))
        assertEquals("Original", restored.auth.names[FakeFixtures.ALEX])
        assertFalse(restored.auth.challenges.containsKey("r"))
    }

}
