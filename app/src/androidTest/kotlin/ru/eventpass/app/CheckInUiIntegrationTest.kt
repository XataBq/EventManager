package ru.eventpass.app

import android.Manifest
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.platform.*
import ru.eventpass.data.fake.product.FakeFixtures

class CheckInUiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
    }
    @Before fun prepare() {
        runBlocking { compose.backend().environment().scenarios.reset() }; compose.login()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    private fun awaitTag(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun back() { compose.waitForIdle(); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun openScanner(id: String = FakeFixtures.MIXED.value) {
        compose.onNodeWithTag("tab-Events").performClick()
        if (id != FakeFixtures.OWNED.value) compose.onNodeWithText(compose.activity.getString(R.string.my_volunteering)).performScrollTo().performClick()
        compose.onNodeWithTag("scan-$id").performScrollTo().performClick(); awaitTag("scan-input")
    }
    private fun submit(code: String) {
        compose.onNodeWithTag("scan-input").performScrollTo().performTextReplacement(code)
        compose.onNodeWithTag("scan-submit").performScrollTo().performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    @Test fun manualInvalidTransportAdmittedRepeatAndBackUseSameEntry() {
        openScanner()
        val e = compose.backend().environment()
        submit("23456789ABCD"); awaitTag("scan-result")
        compose.onNodeWithText(compose.activity.getString(R.string.scan_invalid)).assertExists()
        back(); awaitTag("scan-input")
        val mine = runBlocking { e.tickets.observeMyTickets().first() as MyTicketsResult.Ready }
        val ticket = mine.tickets.single { it.snapshot.eventId == FakeFixtures.MIXED }
        runBlocking { e.scenarios.failNextCheckIn(CheckInError.TIMEOUT) }
        submit(ticket.snapshot.manualCode.value)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.auth_timeout)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scan-result").assertDoesNotExist()
        submit(ticket.snapshot.manualCode.value); awaitTag("scan-result")
        compose.onNodeWithText(compose.activity.getString(R.string.scan_admitted)).assertExists()
        screenshot("t07-result.png")
        compose.activityRule.scenario.recreate(); awaitTag("scan-input")
        submit(ticket.snapshot.manualCode.value); awaitTag("scan-result")
        compose.onNodeWithText(compose.activity.getString(R.string.scan_used)).assertExists()
        back(); awaitTag("scan-input"); back(); awaitTag("root-Events")
        compose.onNodeWithTag("tab-Events").assertIsSelected()
    }
    @Test fun ownerGrantsExistingAccountAndConfirmsRevokeWithoutLosingOwnTicket() {
        val e = compose.backend().environment()
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("volunteers-alex-owned").performScrollTo().performClick(); awaitTag("volunteer-input")
        compose.onNodeWithTag("volunteer-input").performTextReplacement("missing@example.com")
        compose.onNodeWithTag("volunteer-grant").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.volunteer_unknown)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("volunteer-input").performScrollTo().performTextReplacement("host@example.com")
        compose.onNodeWithTag("volunteer-grant").performScrollTo().performClick(); awaitTag("volunteer-host")
        androidx.test.espresso.Espresso.closeSoftKeyboard(); screenshot("t07-volunteers.png")
        compose.onNodeWithText(compose.activity.getString(R.string.volunteer_revoke)).performScrollTo().performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.volunteer_revoke)).onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("volunteer-host").fetchSemanticsNodes().isEmpty() }
        androidx.test.espresso.Espresso.closeSoftKeyboard(); back(); awaitTag("root-Events")
        compose.onNodeWithTag("scan-alex-owned").performScrollTo().performClick(); awaitTag("scan-input")
        assertEquals(2, (runBlocking { e.tickets.observeMyTickets().first() } as MyTicketsResult.Ready).tickets.size)
    }
    private fun cameraView(view: View): QrCameraPreview? = if (view is QrCameraPreview) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { cameraView(group.getChildAt(it)) } }
    private fun startCamera(): QrCameraPreview {
        instrumentation.uiAutomation.grantRuntimePermission("ru.eventpass.android.demo", Manifest.permission.CAMERA)
        compose.onNodeWithTag("scan-camera").performScrollTo().performClick()
        var found: QrCameraPreview? = null
        compose.waitUntil(15_000) { compose.runOnIdle { found = cameraView(compose.activity.window.decorView) }; found?.isCameraBound == true && found?.isStreaming == true }
        return found!!
    }
    @Test fun cameraIsReleasedOnHiddenTabBackgroundBackAndRevoke() {
        openScanner(); val first = startCamera()
        screenshot("t07-camera.png")
        compose.onNodeWithTag("tab-Profile").performClick(); compose.waitUntil(10_000) { !first.isCameraBound }
        compose.onNodeWithTag("tab-Events").performClick(); awaitTag("scan-input")
        val second = startCamera()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { !second.isCameraBound }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); awaitTag("scan-input")
        val third = startCamera(); back(); awaitTag("root-Events"); assertFalse(third.isCameraBound)
        compose.onNodeWithTag("scan-alex-mixed").performScrollTo().performClick(); awaitTag("scan-input")
        val fourth = startCamera()
        runBlocking { compose.backend().environment().scenarios.setGrant(ScanGrant(FakeFixtures.MIXED, FakeFixtures.ALEX, false)) }
        awaitTag("root-Events"); compose.waitUntil(10_000) { !fourth.isCameraBound }
        compose.onNodeWithTag("scanner-screen").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.dismiss)).performClick()
        compose.onNodeWithTag("tab-Tickets").performClick()
        compose.onNodeWithTag("ticket-seed-ticket-alex-mixed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("scan-alex-volunteer").performScrollTo().performClick(); awaitTag("scan-input")
        val fifth = startCamera()
        runBlocking { compose.backend().environment().auth.signOut() }
        awaitTag("login-screen"); compose.waitUntil(10_000) { !fifth.isCameraBound }
    }
    @Test fun realDecoderOnControlledFrameFeedsScannerAndDenialKeepsManualAvailable() {
        openScanner()
        // Permission state is prepared as denied by adb before this one-test run.
        if (compose.activity.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            compose.onNodeWithTag("scan-camera").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                val node = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")?.firstOrNull()
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            awaitTag("scan-input")
        }
        val qr = TicketQrEncoder.encode("demo-token-alex-mixed", 256)
        val decoded = QrFrameDecoder.decode(ByteArray(qr.pixels.size) { qr.pixels[it].toByte() }, qr.size, qr.size)
        assertEquals(listOf("demo-token-alex-mixed"), decoded)
        // Feed the real decoder result through the same controller API used by CameraX.
        val vm = ru.eventpass.app.feature.checkin.ScannerViewModel(compose.backend().environment().checkIns)
        try {
            compose.runOnIdle { vm.bind(FakeFixtures.MIXED.value); vm.foreground(true) }
            runBlocking { vm.state.first { it.access != null } }
            compose.runOnIdle { vm.permission(ScannerPermission.GRANTED); vm.camera(); vm.frame(vm.state.value.generation, decoded) }
            assertTrue(runBlocking { vm.state.first { it.phase == ScannerPhase.RESULT } }.result is CheckInOutcome.Admitted)
        } finally { compose.runOnIdle { androidx.lifecycle.ViewModelStore().apply { put("scanner", vm); clear() } } }
        submit(ManualCodePolicy.fromQr("demo-token-alex-mixed").value); awaitTag("scan-result")
        compose.onNodeWithText(compose.activity.getString(R.string.scan_used)).assertExists()
    }
}
