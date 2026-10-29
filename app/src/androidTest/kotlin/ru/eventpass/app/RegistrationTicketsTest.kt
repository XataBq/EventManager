package ru.eventpass.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.data.fake.product.FakeFixtures
import ru.eventpass.core.domain.registration.*

class RegistrationTicketsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun open(id: String = "open-registration") {
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("eventpass-demo://demo/e/$id"), it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("event-$id").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun form(email: String, authenticated: Boolean = false) {
        compose.onNodeWithTag("registration-name").performScrollTo().performTextReplacement("Мария 李")
        if (!authenticated) compose.onNodeWithTag("registration-email").performScrollTo().performTextReplacement(email)
        compose.onNodeWithTag("registration-consent").performScrollTo().performClick()
        compose.onNodeWithTag("registration-submit").performScrollTo().performClick()
    }
    @Test fun guestReceiptIsOneSnapshotAcrossBackRecreationAndFlowRemoval() {
        runBlocking { compose.backend().environment().scenarios.reset() }
        open()
        val email = "receipt-${System.nanoTime()}@example.com"
        form(email)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guest-receipt").fetchSemanticsNodes().isNotEmpty() }
        val e = compose.backend().environment()
        val ticket = e.scenarios.outbox().single().ticketId
        assertEquals(2, e.scenarios.accounts().size)
        compose.onNodeWithTag("open-fullscreen").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ticket-qr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("close-fullscreen").performClick()
        compose.onNodeWithText("Войти").performScrollTo().performClick()
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("guest-receipt").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guest-receipt").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ticket, e.scenarios.outbox().single().ticketId)
        compose.onNodeWithTag("close-guest-flow").performScrollTo().performClick()
        open()
        compose.onNodeWithTag("guest-receipt").assertDoesNotExist()
        form(email)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Заявка уже есть. Воспользуйтесь письмом либо создайте аккаунт и войдите. Повторная заявка не раскрывает QR.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("guest-receipt").assertDoesNotExist()
        assertEquals(1, e.scenarios.outbox().size)
    }

    @Test fun authenticatedSubmitSelectsTicketAndCancellationMovesItToHistory() {
        runBlocking { compose.backend().environment().scenarios.reset() }
        compose.login()
        open(); form("ignored", authenticated = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("selected-ticket").fetchSemanticsNodes().isNotEmpty() }
        val mine = runBlocking { compose.backend().environment().tickets.observeMyTickets().first() } as MyTicketsResult.Ready
        val ticket = mine.tickets.single { it.snapshot.eventId == FakeFixtures.OPEN }
        compose.onNodeWithTag("open-fullscreen").performScrollTo().performClick()
        compose.onNodeWithTag("close-fullscreen").performClick()
        compose.onNodeWithTag("cancel-ticket").performScrollTo().performClick()
        compose.onNodeWithText("Отменить билет?").assertIsDisplayed()
        compose.onAllNodesWithText("Отменить регистрацию").onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Отменён").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отменён").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open-fullscreen").assertDoesNotExist()
        val changed = runBlocking { compose.backend().environment().tickets.observeMyTickets().first() } as MyTicketsResult.Ready
        assertEquals(TicketStatus.CANCELLED, changed.tickets.single { it.snapshot.ticketId == ticket.snapshot.ticketId }.status)
    }

    @Test fun existingEmailReturnsToFreshFormAfterManualLoginWithoutSubmission() {
        runBlocking { compose.backend().environment().scenarios.reset() }
        open(); form("alex@example.com")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("login-screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth-email").assertTextEquals("alex@example.com")
        compose.onNodeWithTag("auth-password").performScrollTo().performTextInput("DemoPass123!")
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("event-open-registration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("registration-name").performScrollTo().assertTextEquals("")
        compose.onNodeWithTag("registration-consent").performScrollTo().assertIsOff()
        assertTrue(compose.backend().environment().scenarios.outbox().isEmpty())
    }

    @Test fun separateSignupFromReceiptKeepsQrOnBackAndManualLoginShowsIssuedTicket() {
        runBlocking { compose.backend().environment().scenarios.reset() }
        val email = "signup-receipt-${System.nanoTime()}@example.com"
        open(); form(email)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guest-receipt").fetchSemanticsNodes().isNotEmpty() }
        val ticket = compose.backend().environment().scenarios.outbox().single().ticketId
        compose.onNodeWithText("Создать аккаунт").performScrollTo().performClick()
        compose.onNodeWithTag("auth-email").assertTextEquals(email)
        compose.onNodeWithTag("auth-name").performTextInput("Мария 李")
        compose.onNodeWithTag("auth-password").performScrollTo().performTextInput("GuestAccount123!")
        compose.onNodeWithTag("auth-consent").performScrollTo().performClick()
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Отправить повторно").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Имитация почты").performScrollTo().performClick()
        compose.onNodeWithText("Открыть подтверждение").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Email подтверждён. Войдите вручную.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("guest-receipt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Войти").performScrollTo().performClick()
        compose.login(email, "GuestAccount123!", prepareGuest = false)
        compose.onNodeWithTag("ticket-${ticket.value}").performScrollTo().assertIsDisplayed()
        val mine = runBlocking { compose.backend().environment().tickets.observeMyTickets().first() } as MyTicketsResult.Ready
        assertEquals(listOf(ticket), mine.tickets.map { it.snapshot.ticketId })
    }

    @Test fun repeatedLinkFromAuthNeighbourReusesEventAndUnknownIdShowsNotFound() {
        runBlocking { compose.backend().environment().scenarios.reset() }
        open()
        compose.onNodeWithTag("event-login").performScrollTo().performClick()
        open()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithTag("back").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("eventpass-demo://demo/e/unknown-event"), it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("event-not-found").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("event-not-found").assertIsDisplayed()
        compose.onNodeWithTag("registration-submit").assertDoesNotExist()
    }
}
