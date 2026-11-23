package ru.eventpass.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.eventpass.app.navigation.PdfVaultKey
import ru.eventpass.app.navigation.RootTab
import ru.eventpass.app.navigation.RootTabNavDisplay
import ru.eventpass.core.designsystem.EventPassTheme

class PdfNavigationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pdfAndAppHeadersSwitchTogetherAtFrameBoundary() {
        compose.setContent {
            EventPassTheme {
                val stack = rememberNavBackStack(RootTab.Tickets)
                val showAppBar = stack.last() !is PdfVaultKey
                val entries = rememberDecoratedNavEntries(
                    backStack = stack,
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                    entryProvider = entryProvider {
                        entry<RootTab> {
                            Column(Modifier.testTag("tickets-screen")) {
                                Text("Мои билеты")
                                Button(onClick = { stack.add(PdfVaultKey(false)) }, Modifier.testTag("pdf-open")) {
                                    Text("Билеты из PDF")
                                }
                            }
                        }
                        entry<PdfVaultKey> {
                            Button(onClick = { stack.removeLastOrNull() }, Modifier.testTag("pdf-back")) { Text("Назад") }
                        }
                    },
                )
                Scaffold(
                    topBar = { if (showAppBar) Box(Modifier.height(56.dp).testTag("app-header")) },
                    contentWindowInsets = WindowInsets(0),
                ) { padding ->
                    RootTabNavDisplay(RootTab.Tickets, entries, { stack.removeLastOrNull() },
                        Modifier.fillMaxSize().padding(padding), showAppBar)
                }
            }
        }

        val tickets = compose.onNodeWithTag("tickets-screen")
        val appHeader = compose.onNodeWithTag("app-header")
        val ticketsTop = tickets.fetchSemanticsNode().boundsInRoot.top
        assertTrue(ticketsTop > 0f)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("pdf-open").performClick()
            compose.mainClock.advanceTimeBy(32L)
            tickets.assertDoesNotExist()
            appHeader.assertDoesNotExist()
            compose.onNodeWithTag("pdf-back").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(32L)
            appHeader.assertExists()
            compose.onNodeWithTag("pdf-back").assertDoesNotExist()
            assertEquals(ticketsTop, tickets.fetchSemanticsNode().boundsInRoot.top, 0.5f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
