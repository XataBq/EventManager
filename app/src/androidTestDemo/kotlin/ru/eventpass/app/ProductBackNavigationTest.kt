package ru.eventpass.app

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.eventpass.core.domain.event.TicketId
import ru.eventpass.core.domain.registration.TicketStatus

/** Uses real KEYCODE_BACK, including Dialog windows, rather than calling navigation callbacks. */
class ProductBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ticketId = "seed-ticket-alex-attending"

    @Before fun prepare() {
        runBlocking { compose.backend().environment().scenarios.reset() }
        compose.login()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun back() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun selectTicket() {
        compose.onNodeWithTag("ticket-$ticketId").performScrollTo().performClick()
        compose.onNodeWithTag("selected-ticket").assertExists()
    }

    private fun assertTicketList() {
        // System Back is delivered through the window; await its observable result.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("selected-ticket").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("root-Tickets").assertIsDisplayed()
        compose.onNodeWithTag("selected-ticket").assertDoesNotExist()
        compose.onNodeWithTag("ticket-$ticketId").performScrollTo().assertIsDisplayed()
    }

    @Test fun selectedTicketSystemBackReturnsToListAfterRecreation() {
        selectTicket()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("selected-ticket").assertExists()
        back()
        assertTicketList()
        // A removed selection must not reappear from the root SavedStateHandle.
        compose.activityRule.scenario.recreate()
        assertTicketList()
    }

    @Test fun historyTicketSystemBackKeepsHistoryAndClearsSelection() {
        runBlocking { compose.backend().environment().scenarios.setTicketStatus(TicketId(ticketId), TicketStatus.CANCELLED) }
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_history)).performScrollTo().performClick()
        selectTicket()
        back()
        assertTicketList()
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_history)).assertIsSelected()
    }

    @Test fun dialogAndEventBackReturnToCardBeforeReturningToList() {
        selectTicket()
        compose.onNodeWithTag("open-fullscreen").performScrollTo().performClick()
        compose.onNodeWithTag("close-fullscreen").assertIsDisplayed()
        back()
        compose.onNodeWithTag("close-fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("selected-ticket").assertExists()
        compose.onNodeWithTag("cancel-ticket").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_cancel_title)).assertIsDisplayed()
        back()
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_cancel_title)).assertDoesNotExist()
        compose.onNodeWithTag("selected-ticket").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.ticket_to_event)).performScrollTo().performClick()
        compose.onNodeWithTag("event-alex-attending").assertExists()
        back()
        compose.onNodeWithTag("selected-ticket").assertExists()
        back()
        assertTicketList()
    }

    @Test fun myEventsPageBackKeepsOriginAndListSegmentAcrossRecreationAndTabSwitch() {
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.editor_open)).onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("event-alex-owned").assertExists()
        compose.onNodeWithTag("tab-Events").assertIsSelected()
        compose.activityRule.scenario.recreate()
        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("root-Events").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("root-Events").assertIsDisplayed()
        compose.onNodeWithTag("tab-Events").assertIsSelected()

        compose.onNodeWithText(compose.activity.getString(R.string.my_volunteering)).performScrollTo().performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.editor_open)).onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.onNodeWithTag("tab-Events").performClick()
        back()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("root-Events").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("root-Events").assertIsDisplayed()
        compose.onNodeWithTag("tab-Events").assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.my_volunteering)).assertIsSelected()
    }
}
