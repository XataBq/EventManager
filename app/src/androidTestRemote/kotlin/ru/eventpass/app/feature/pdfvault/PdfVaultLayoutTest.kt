package ru.eventpass.app.feature.pdfvault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import ru.eventpass.core.designsystem.EventPassTheme
import ru.eventpass.core.domain.importedticket.TicketField

class PdfVaultLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeKeepsTheCodeDialogOpenAndReturnsToTheSameTicket() {
        val first = ImportedTicket("group", 0, mapOf(TicketField.TITLE to "Первый билет"), "sample.pdf",
            ImportedCode("QR_CODE", "synthetic-1"))
        val second = first.copy(page = 1, fields = mapOf(TicketField.TITLE to "Второй билет"),
            code = ImportedCode("CODE_128", "synthetic-2"))
        val group = listOf(first, second)
        compose.setContent {
            EventPassTheme {
                var current by remember { mutableStateOf(first) }
                val open: (ImportedTicket?) -> Unit = { if (it != null) current = it }
                Column(Modifier.fillMaxSize().testTag("pdf-card").ticketSwipe(current, group, open)
                    .verticalScroll(rememberScrollState())) {
                    StoredTicket(current, group, open, { _, _ -> error("No PDF in layout fixture") }) {}
                }
            }
        }
        compose.onNodeWithTag("pdf-card").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Второй билет").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Код билета CODE_128").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Код билета CODE_128").performClick()
        compose.onNodeWithTag("pdf-full-code").performTouchInput { swipeRight() }
        compose.onNodeWithText("Билет 1 из 2").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithText("Первый билет").assertIsDisplayed()
    }

    @Test fun narrowCardWithLargeTextKeepsAddressAndExtraFieldsReachable() {
        val title = "Длинное название музыкального мероприятия в большом концертном зале"
        val address = "Набережная Речного Вокзала, 120, корпус 4, центральный вход со стороны площади"
        val extra = "Льготный билет для посетителей с действующим студенческим удостоверением"
        val ticket = ImportedTicket("test", 0, mapOf(
            TicketField.TITLE to title, TicketField.ADDRESS to address, TicketField.SECTOR to "Северная трибуна",
            TicketField.ROW to "15", TicketField.SEAT to "28", TicketField.ENTRANCE to "Вход со стороны площади",
        ), "fixture.pdf", null, mapOf("Тариф" to extra))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                EventPassTheme {
                    Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
                        StoredTicket(ticket, listOf(ticket), {}, { _, _ -> error("No PDF in layout fixture") }) {}
                    }
                }
            }
        }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(extra).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Открыть оригинал PDF").performScrollTo().assertIsDisplayed()
    }
}
