package ru.eventpass.app

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.data.fake.product.FakeFixtures

/** Real picker/configuration lifecycle; controlled URI delivered by the real ActivityResultRegistry. */
class SafRecreationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun await(tag: String) {compose.waitUntil(15_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use {reader->reader.readText()}
    }
    private fun openPicker(): Int {
        val activity=compose.activity
        compose.onNodeWithTag("document-export").performScrollTo().assertIsEnabled().performClick();compose.waitForIdle()
        val deadline=SystemClock.uptimeMillis()+15000;var resumed=false
        while(!resumed && SystemClock.uptimeMillis()<deadline) {
            resumed=shell("dumpsys activity activities").lineSequence().any {it.contains("topResumedActivity=") && it.contains("documentsui")}
            if(!resumed) SystemClock.sleep(100)
        }
        assertTrue("DocumentsUI did not open",resumed)
        val saved=Bundle();compose.runOnUiThread {activity.activityResultRegistry.onSaveInstanceState(saved)}
        // AndroidX registry's saved routing table, test-only: select the actually launched key, not a guessed code.
        val launched=saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")!!.single()
        val keys=saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
        return saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!![keys.indexOf(launched)]
    }
    private fun deliver(code: Int, file: String, magic: String) {
        // ActivityScenario requires RESUMED for recreate(), while a real picker stops its host.
        // Bring the existing task entry forward without creating another MainActivity/VM owner.
        shell("am start -W -f 0x00020000 -n ru.eventpass.android.demo/ru.eventpass.app.MainActivity")
        compose.waitForIdle()
        val uri=Uri.parse("content://ru.eventpass.android.demo.test.documents/$file")
        compose.runOnUiThread {assertTrue(compose.activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)))}
        await("document-saved")
        val bytes=compose.activity.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
        assertTrue("Export was empty",bytes.size>1000);assertEquals(magic,bytes.take(magic.length).toByteArray().toString(Charsets.US_ASCII))
    }
    @Test fun pdfResultAfterActivityRecreationWritesSelectedTicket() {
        val e=compose.backend().environment();runBlocking {e.scenarios.reset();e.scenarios.signInAs(FakeFixtures.ALEX)};await("root-Tickets")
        compose.onNodeWithTag("ticket-seed-ticket-alex-mixed").performScrollTo().performClick();await("selected-ticket")
        val old=compose.activity
        try {
            val code=openPicker();InstrumentationRegistry.getInstrumentation().runOnMainSync {old.recreate()}
            val deadline=SystemClock.uptimeMillis()+15_000
            while(!old.isDestroyed && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100)
            assertTrue(old.isDestroyed)
            deliver(code,"ticket.pdf","%PDF");assertNotSame(old,compose.activity)
        } finally {shell("am force-stop com.google.android.documentsui")}
    }
    @Test fun xlsxResultAfterRotationWritesOwnerSnapshot() {
        val e=compose.backend().environment();runBlocking {e.scenarios.reset();e.scenarios.signInAs(FakeFixtures.HOST)};await("root-Tickets")
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("dashboard-${FakeFixtures.MIXED.value}").performScrollTo().performClick();await("dashboard-query")
        val old=compose.activity
        try {
            val code=openPicker();assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            deliver(code,"flow.xlsx","PK");assertNotSame(old,compose.activity)
        } finally {automation.setRotation(UiAutomation.ROTATION_FREEZE_0);shell("am force-stop com.google.android.documentsui")}
    }
}
