package ru.eventpass.data.fake.product

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

/** Old writers preserved email case; these fixtures exercise identity without rewriting it. */
class FakeLegacyEmailRecoveryTest {
    private class Disk(initial: ByteArray) : FakeAuthStorage {
        var bytes = initial.copyOf()
        override fun read() = bytes.copyOf()
        override fun write(snapshot: ByteArray) { bytes = snapshot.copyOf() }
    }
    private val legacy = Account(UserId("legacy-upper"), "Mixed@Example.com", false)
    private val collision = Account(UserId("legacy-lower"), "mixed@example.com", false)
    private val password = AuthSecret("LegacyMixed123!")
    private fun <T> value(result: AuthResult<T>) = (result as AuthResult.Success).value
    private fun state(collisionPresent: Boolean = false): FakeProductState {
        val extras = if (collisionPresent) listOf(legacy, collision) else listOf(legacy)
        val accounts = (FakeFixtures.accounts() + extras).associateBy { it.id }
        val events = FakeFixtures.events()
        val event = events.single { it.id == FakeFixtures.OPEN }
        val registrations = extras.map { account -> Registration("reg-" + account.id.value, event.id, account.id,
            account.email, "Legacy", null, 1, emptyMap(), FakeFixtures.NOW) }
        val tickets = registrations.map { reg -> Ticket(GuestTicketSnapshot(TicketId("ticket-" + reg.id), event.id,
            event.title, event.startsAt, event.zoneId, event.venue, reg.attendeeName, "LEGACY-" + reg.id,
            "opaque-legacy-" + reg.id), reg.id, TicketStatus.ACTIVE) }
        return FakeProductState(accounts, events.associateBy { it.id }, FakeFixtures.registrations() + registrations,
            FakeFixtures.tickets(events) + tickets, FakeFixtures.grants() + extras.map { ScanGrant(event.id, it.id) },
            auth = AuthData(passwords = accounts.mapValues { (id, _) -> passwordProof(
                if (id == collision.id) AuthSecret("DifferentProof123!") else password, 1000) },
                names = accounts.mapValues { "Saved name " + it.key.value }))
    }
    private fun env(disk: Disk) = FakeEventPassEnvironment(authStorage = disk, authPolicy = FakeAuthPolicy(passwordIterations = 1000))
    private fun restored(disk: Disk, original: FakeProductState) = ProductSnapshotCodec.restore(disk.bytes, original)
    private fun assertIdentity(original: FakeProductState, actual: FakeProductState) {
        assertEquals(original.accounts, actual.accounts)
        assertEquals(original.auth.passwords, actual.auth.passwords)
        assertEquals(original.auth.names, actual.auth.names)
        assertEquals(original.registrations, actual.registrations)
        assertEquals(original.tickets, actual.tickets)
        assertEquals(original.grants, actual.grants)
    }

    @Test fun mixedCaseLegacyProductSupportsLoginAndRecoveryWithoutRewritingIdentity() = runTest {
        val original = state(); val disk = Disk(LegacyV2Fixture.encode(original)); val e = env(disk)
        assertEquals(SessionState.Guest, e.auth.observeSession().first().state)
        for (email in listOf("Mixed@Example.com", "mixed@example.com", " MIXED@EXAMPLE.COM ")) {
            val session = value(e.auth.signIn(email, password))
            assertEquals(legacy, session.account)
            assertEquals(listOf(TicketId("ticket-reg-legacy-upper")),
                (e.tickets.observeMyTickets().first() as MyTicketsResult.Ready).tickets.map { it.snapshot.ticketId })
            e.auth.signOut()
            assertEquals(AuthResult.Failed(AuthError.EmailTaken), e.auth.signUp("New", email, password, true))
        }
        value(e.auth.requestVerification(" MIXED@example.COM "))
        assertEquals(legacy.email, e.scenarios.authOutbox().last().challenge.email)
        assertFalse(e.scenarios.authOutbox().last().reset)
        value(e.auth.requestPasswordReset("mixed@example.com"))
        assertEquals(legacy.email, e.scenarios.authOutbox().last().challenge.email)
        assertTrue(e.scenarios.authOutbox().last().reset)
        assertEquals(2, e.scenarios.authOutbox().size)
        assertIdentity(original, restored(disk, original))
        val cold = env(disk)
        assertEquals(legacy.id, value(cold.auth.signIn("MIXED@EXAMPLE.COM", password)).account.id)
        assertIdentity(original, restored(disk, original))
    }

    @Test fun mixedCaseStandaloneLegacyAuthSupportsColdLoginAndRejectsDuplicateSignup() = runTest {
        val original = state(); val disk = Disk(LegacyAuthV1Fixture.encode(original.accounts, original.auth)); val e = env(disk)
        assertEquals(legacy, value(e.auth.signIn("mixed@example.com", password)).account)
        assertEquals(AuthResult.Failed(AuthError.EmailTaken), e.auth.signUp("Duplicate", "MIXED@EXAMPLE.COM", password, true))
        assertEquals(original.accounts, restored(disk, original).accounts)
        assertEquals(original.auth.passwords, restored(disk, original).auth.passwords)
    }

    @Test fun normalizedLegacyCollisionFailsClosedWithoutDamagingUnrelatedAccounts() = runTest {
        val original = state(true); val disk = Disk(LegacyV2Fixture.encode(original)); val originalBytes = disk.bytes.copyOf(); val e = env(disk)
        assertEquals(SessionState.Guest, e.auth.observeSession().first().state)
        for (email in listOf("Mixed@Example.com", "mixed@example.com", "MIXED@EXAMPLE.COM")) {
            assertEquals(AuthResult.Failed(AuthError.InvalidCredentials), e.auth.signIn(email, password))
            assertEquals(AuthResult.Failed(AuthError.InvalidCredentials), e.auth.signIn(email, AuthSecret("DifferentProof123!")))
            value(e.auth.requestVerification(email)); value(e.auth.requestPasswordReset(email))
            assertEquals(AuthResult.Failed(AuthError.EmailTaken), e.auth.signUp("Third", email, password, true))
        }
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        assertTrue(e.scenarios.authOutbox().isEmpty())
        assertArrayEquals(originalBytes, disk.bytes)
        assertEquals(original.accounts.size, e.scenarios.accounts().size)
        assertIdentity(original, restored(disk, original))
        val unrelated = value(e.auth.signIn(" ALEX@EXAMPLE.COM ", password))
        assertEquals(FakeFixtures.ALEX, unrelated.account.id)
        assertIdentity(original, restored(disk, original))
        val created = value(e.auth.signUp("Independent", "independent@example.com", password, true))
        assertNotEquals(legacy.id, created.account.id); assertNotEquals(collision.id, created.account.id)
        val after = restored(disk, original)
        assertEquals(original.accounts, after.accounts.filterKeys { it != created.account.id })
        assertEquals(original.auth.passwords, after.auth.passwords.filterKeys { it != created.account.id })
        assertEquals(original.registrations, after.registrations); assertEquals(original.tickets, after.tickets)
    }
}
