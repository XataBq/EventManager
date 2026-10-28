package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    private class Disk : FakeAuthStorage {
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
    private suspend fun create(e: FakeEventPassEnvironment, email: String = "new@example.com") = value(e.auth.signUp("李", email, password))
    private suspend fun confirm(e: FakeEventPassEnvironment, email: String = "new@example.com") {
        val mail = e.scenarios.authOutbox().last { !it.reset && it.challenge.email == email }
        value(e.auth.confirmEmail(mail.challenge.id, mail.secret))
    }

    @Test fun signUpAndConfirmationRequireManualLogin() = runTest {
        val e = env()
        assertEquals(SessionState.Unknown, e.auth.currentSession().state)
        assertEquals(SessionState.Guest, e.auth.observeSession().first().state)
        val challenge = create(e)
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        failure(AuthError.EmailTaken, e.auth.signUp("Other", "new@example.com", password))
        failure(AuthError.InvalidCredentials, e.auth.signIn("new@example.com", AuthSecret("wrong")))
        failure(AuthError.EmailUnverified, e.auth.signIn("new@example.com", password))
        confirm(e)
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        val session = value(e.auth.signIn(challenge.email, password))
        assertTrue(session.account.emailVerified)
        e.auth.signOut()
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
    }

    @Test fun guestTicketsAppearOnlyAfterSeparateConfirmedAccountLogin() = runTest {
        val e = env()
        val command = GuestRegistrationCommand(FakeFixtures.OPEN, 1, "李", "new@example.com", consentAccepted = true)
        val ticket = (e.registrations.submitGuest(command) as GuestRegistrationOutcome.Issued).ticket
        assertEquals(2, e.scenarios.accounts().size)
        create(e); confirm(e); value(e.auth.signIn("new@example.com", password))
        assertEquals(listOf(ticket.ticketId), (e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.map { it.snapshot.ticketId })
        e.auth.signOut()
        assertEquals(MyTicketsResult.AuthenticationRequired, e.tickets.observeMyTickets().first())
        create(e, "other@example.com"); confirm(e, "other@example.com"); value(e.auth.signIn("other@example.com", password))
        assertTrue((e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.isEmpty())
        assertEquals(ticket.ticketId, e.scenarios.outbox().single().ticketId)
    }

    @Test fun confirmationCooldownResendInvalidatesOldTokenAndConsumptionIsOneTime() = runTest {
        val time = Time(); val e = env(time)
        val c = create(e); val old = e.scenarios.authOutbox().last()
        failure(AuthError.Cooldown(c.resendAt), e.auth.requestVerification(c.email))
        assertEquals(1, e.scenarios.authOutbox().size)
        time.advance(60)
        val replacement = value(e.auth.requestVerification(c.email)); val mail = e.scenarios.authOutbox().last()
        failure(AuthError.InvalidChallenge, e.auth.confirmEmail(c.id, old.secret))
        failure(AuthError.InvalidChallenge, e.auth.confirmEmail(replacement.id, AuthSecret("bad")))
        value(e.auth.confirmEmail(replacement.id, mail.secret))
        failure(AuthError.ConsumedChallenge, e.auth.confirmEmail(replacement.id, mail.secret))
        failure(AuthError.AlreadyVerified, e.auth.requestVerification(c.email))
    }

    @Test fun resetWorksWithoutVerificationButNeverConfirmsEmailOrLogsIn() = runTest {
        val e = env(); create(e)
        val c = value(e.auth.requestPasswordResetCode("new@example.com")); val mail = e.scenarios.authOutbox().last()
        failure(AuthError.InvalidChallenge, e.auth.verifyResetCode(c.id, AuthSecret("wrong")))
        val grant = value(e.auth.verifyResetCode(c.id, mail.secret))
        failure(AuthError.ConsumedChallenge, e.auth.verifyResetCode(c.id, mail.secret))
        failure(AuthError.InvalidInput, e.auth.resetPassword(grant, AuthSecret("short")))
        value(e.auth.resetPassword(grant, AuthSecret("UpdatedPass1!")))
        failure(AuthError.ConsumedChallenge, e.auth.resetPassword(grant, password))
        failure(AuthError.InvalidCredentials, e.auth.signIn("new@example.com", password))
        failure(AuthError.EmailUnverified, e.auth.signIn("new@example.com", AuthSecret("UpdatedPass1!")))
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        confirm(e)
        value(e.auth.signIn("new@example.com", AuthSecret("UpdatedPass1!")))
    }

    @Test fun expiryRejectsConfirmationCodeAndResetGrant() = runTest {
        val time = Time(); val e = env(time)
        val confirmation = create(e); val confirmMail = e.scenarios.authOutbox().last()
        val reset = value(e.auth.requestPasswordResetCode(confirmation.email)); val code = e.scenarios.authOutbox().last().secret
        val grant = value(e.auth.verifyResetCode(reset.id, code))
        time.advance(900)
        failure(AuthError.ExpiredChallenge, e.auth.confirmEmail(confirmation.id, confirmMail.secret))
        failure(AuthError.ExpiredChallenge, e.auth.resetPassword(grant, password))
        val c = value(e.auth.requestPasswordResetCode(confirmation.email)); val code2 = e.scenarios.authOutbox().last().secret
        time.advance(900)
        failure(AuthError.ExpiredChallenge, e.auth.verifyResetCode(c.id, code2))
    }

    @Test fun sessionExpiryGuardsTicketsAndPublishesGuestOnRefresh() = runTest {
        val time = Time(); val e = env(time)
        val first = value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        time.advance(1800)
        assertEquals(MyTicketsResult.AuthenticationRequired, e.tickets.observeMyTickets().first())
        e.auth.refreshSession()
        assertEquals(SessionState.Guest, e.auth.observeSession().first().state)
        val next = value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        assertNotEquals(first.id, next.id)
    }

    @Test fun networkFailuresDoNotSendMailCreateAccountsOrLogin() = runTest {
        val e = env()
        e.scenarios.failNextAuth(AuthError.Network)
        failure(AuthError.Network, e.auth.signUp("Name", "new@example.com", password))
        assertEquals(2, e.scenarios.accounts().size); assertTrue(e.scenarios.authOutbox().isEmpty())
        e.scenarios.failNextAuth(AuthError.Timeout)
        failure(AuthError.Timeout, e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
    }

    @Test fun cancellationAndLateLoginAfterLogoutDoNotMutateSession() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val e = env(hook = { if (it == "signIn") { entered.complete(Unit); release.await() } })
        val task = async { e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")) }
        entered.await(); e.auth.signOut(); release.complete(Unit)
        failure(AuthError.SessionChanged, task.await())
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        val cancelled = env(hook = { throw kotlinx.coroutines.CancellationException("test cancellation") })
        val job = async { cancelled.auth.signUp("Name", "x@example.com", password) }
        job.join(); assertTrue(job.isCancelled)
        assertEquals(2, cancelled.scenarios.accounts().size)
    }

    @Test fun coldRestartRestoresAccountChallengeAndPasswordProofButNoSessionOrOutbox() = runTest {
        val disk = Disk(); val time = Time(); val e = env(time, disk)
        val c = create(e); val mail = e.scenarios.authOutbox().last()
        val bytesText = disk.bytes!!.toString(Charsets.ISO_8859_1)
        assertFalse(bytesText.contains(password.value)); assertFalse(bytesText.contains(mail.secret.value))
        val cold = env(time, disk)
        assertEquals(SessionState.Guest, cold.auth.observeSession().first().state)
        assertTrue(cold.scenarios.authOutbox().isEmpty())
        value(cold.auth.confirmEmail(c.id, mail.secret))
        value(cold.auth.signIn(c.email, password))
        val again = env(time, disk)
        assertEquals(SessionState.Guest, again.auth.observeSession().first().state)
        failure(AuthError.ConsumedChallenge, again.auth.confirmEmail(c.id, mail.secret))
        value(again.auth.signIn(c.email, password))
        again.scenarios.reset()
        failure(AuthError.InvalidCredentials, again.auth.signIn(c.email, password))
        value(again.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
    }

    @Test fun corruptedSnapshotFailsClosedAndDiskFailureDoesNotPublishSuccess() = runTest {
        val disk = Disk(); disk.bytes = byteArrayOf(0, 0, 0, 99)
        val e = env(disk = disk)
        assertEquals(SessionState.Unknown, e.auth.observeSession().first().state)
        failure(AuthError.StorageUnavailable, e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        e.scenarios.reset(); disk.broken = true
        failure(AuthError.StorageUnavailable, e.auth.signUp("Name", "new@example.com", password))
        assertEquals(2, e.scenarios.accounts().size); assertTrue(e.scenarios.authOutbox().isEmpty())
    }

    @Test fun resentResetInvalidatesOldCodeAndColdResetPersistsNewPassword() = runTest {
        val time = Time(); val disk = Disk(); val e = env(time, disk)
        val old = value(e.auth.requestPasswordResetCode("alex@example.com")); val oldCode = e.scenarios.authOutbox().last().secret
        failure(AuthError.Cooldown(old.resendAt), e.auth.requestPasswordResetCode(old.email))
        time.advance(60)
        val replacement = value(e.auth.requestPasswordResetCode(old.email)); val code = e.scenarios.authOutbox().last().secret
        failure(AuthError.InvalidChallenge, e.auth.verifyResetCode(old.id, oldCode))
        val grant = value(e.auth.verifyResetCode(replacement.id, code))
        val cold = env(time, disk)
        value(cold.auth.resetPassword(grant, AuthSecret("UpdatedDemo123!")))
        assertEquals(SessionState.Guest, cold.auth.currentSession().state)
        val again = env(time, disk)
        failure(AuthError.InvalidCredentials, again.auth.signIn(old.email, AuthSecret("DemoPass123!")))
        value(again.auth.signIn(old.email, AuthSecret("UpdatedDemo123!")))
        failure(AuthError.ConsumedChallenge, again.auth.resetPassword(grant, password))
    }

    @Test fun authWallClockDoesNotChangeTheExistingEventFixtureClock() = runTest {
        val authTime = Time(FakeFixtures.NOW.plusSeconds(86_400 * 5))
        val e = FakeEventPassEnvironment(authPolicy = FakeAuthPolicy(passwordIterations = 1_000), authClock = authTime)
        value(e.auth.signIn("alex@example.com", AuthSecret("DemoPass123!")))
        val event = e.events.observePublicEvent(FakeFixtures.OPEN).first() as ru.eventpass.core.domain.event.PublicEventResult.Found
        assertTrue(event.availability is ru.eventpass.core.domain.event.RegistrationAvailability.Open)
        authTime.advance(1800)
        assertEquals(MyTicketsResult.AuthenticationRequired, e.tickets.observeMyTickets().first())
    }

    @Test fun persistedCooldownRetainsSubsecondDeadline() = runTest {
        val time = Time(FakeFixtures.NOW.plusNanos(987_654_321)); val disk = Disk(); val e = env(time, disk)
        val challenge = create(e)
        val cold = env(time, disk)
        failure(AuthError.Cooldown(challenge.resendAt), cold.auth.requestVerification(challenge.email))
    }
}
