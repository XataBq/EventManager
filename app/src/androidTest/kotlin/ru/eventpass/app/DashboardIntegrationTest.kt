package ru.eventpass.app

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.checkin.ManualCodePolicy
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.data.fake.product.FakeFixtures

class DashboardIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { runBlocking { compose.backend().environment().scenarios.reset() }; compose.login("host@example.com"); androidx.test.espresso.Espresso.closeSoftKeyboard() }
    private fun await(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun back() { compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun reveal(tag: String) { compose.onNodeWithTag("dashboard-screen").performScrollToNode(hasTestTag(tag)) }
    private fun open(id: EventId) {
        compose.onNodeWithTag("tab-Events").performClick(); compose.onNodeWithTag("dashboard-${id.value}").performScrollTo().performClick(); await("dashboard-query")
    }
    @Test fun existingScannerAndVolunteersReturnToDashboardWithUpdatedAttendance() {
        open(FakeFixtures.MIXED); reveal("dashboard-scanner"); compose.onNodeWithTag("dashboard-scanner").performClick(); await("scan-input")
        compose.onNodeWithTag("scan-input").performScrollTo().performTextReplacement(ManualCodePolicy.fromQr("demo-token-alex-mixed").value)
        compose.onNodeWithTag("scan-submit").performScrollTo().performClick(); androidx.test.espresso.Espresso.closeSoftKeyboard(); await("scan-result")
        back(); await("scan-input"); back(); await("dashboard-query")
        compose.onNodeWithTag("dashboard-screen").performScrollToIndex(0)
        compose.onNodeWithText("1 (100%)").assertExists(); compose.onNodeWithTag("tab-Events").assertIsSelected()
        reveal("dashboard-volunteers"); compose.onNodeWithTag("dashboard-volunteers").performClick(); await("volunteer-input")
        back(); await("dashboard-query"); compose.onNodeWithTag("dashboard-event").assertTextEquals("Командный хакатон")
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null),"t08-dashboard.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        back(); await("root-Events")
    }
    @Test fun queryRecreationModalFailuresRetryAndRefreshPreserveSharedData() {
        val e=compose.backend().environment(); open(FakeFixtures.ATTENDING); reveal("dashboard-query")
        compose.onNodeWithTag("dashboard-query").performTextReplacement("missing person")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.dashboard_no_matches)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-query").performTextReplacement("alex")
        androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.activityRule.scenario.recreate(); await("dashboard-query")
        compose.onNodeWithTag("dashboard-query").assertTextEquals("alex")
        reveal("attendee-seed-ticket-alex-attending")
        compose.onNodeWithText(compose.activity.getString(R.string.attendance_cancel)).performClick()
        for (error in listOf(TicketError.NETWORK,TicketError.TIMEOUT,TicketError.STORAGE_UNAVAILABLE)) {
            runBlocking { e.scenarios.failNextCancellation(error) }
            compose.onAllNodes(isDialog() and hasAnyDescendant(hasText("Alex"))).assertCountEquals(1)
            val label=if (error==TicketError.NETWORK) R.string.attendance_cancel else R.string.ticket_retry
            compose.onNode(hasText(compose.activity.getString(label)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            val message=compose.activity.getString(when(error) { TicketError.NETWORK->R.string.auth_network; TicketError.TIMEOUT->R.string.auth_timeout; else->R.string.auth_storage })
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(message) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(message) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
        compose.onNode(hasText(compose.activity.getString(R.string.ticket_retry)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertEquals(0,(runBlocking { e.attendance.observeDashboard(FakeFixtures.ATTENDING).first() } as DashboardResult.Ready).dashboard.summary.registered)
        runBlocking { e.scenarios.failNextAttendanceRead(AttendanceError.TIMEOUT) }
        reveal("dashboard-refresh"); compose.onNodeWithTag("dashboard-refresh").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.auth_timeout)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-event").assertExists()
        compose.onNodeWithTag("tab-Profile").performClick(); compose.onNodeWithTag("tab-Events").performClick(); await("dashboard-query")
        runBlocking { e.auth.signOut() }; await("login-screen"); compose.onNodeWithTag("dashboard-event").assertDoesNotExist()
    }
    @Test fun forgedDashboardForVolunteerRendersNoPrivateRows() {
        compose.login("alex@example.com")
        compose.runOnUiThread { compose.activity.setContent { ru.eventpass.core.designsystem.EventPassTheme {
            ru.eventpass.app.feature.dashboard.DashboardRoute(FakeFixtures.MIXED.value,true,{},{},{})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.scan_access_lost)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-event").assertDoesNotExist(); compose.onNodeWithTag("attendee-seed-ticket-alex-mixed").assertDoesNotExist()
    }
}
