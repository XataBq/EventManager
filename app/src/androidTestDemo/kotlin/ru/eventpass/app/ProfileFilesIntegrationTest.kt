package ru.eventpass.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.platform.AndroidDocumentFiles
import ru.eventpass.data.fake.product.*

@RunWith(AndroidJUnit4::class)
class ProfileFilesIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun await(tag: String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun reveal(tag: String) {compose.onNodeWithTag(tag).performScrollTo()}
    @Test fun profileDirtyBackSaveNewContactAndLogoutUseExistingRoot() {
        val e=compose.backend().environment();runBlocking {e.scenarios.reset()};compose.login();compose.onNodeWithTag("tab-Profile").performClick();await("profile-name")
        compose.onNodeWithTag("profile-name").performTextReplacement("Александра 李明")
        androidx.test.espresso.Espresso.closeSoftKeyboard();compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_unsaved)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_discard)).performClick();await("root-Events")
        compose.onNodeWithTag("tab-Profile").performClick();await("profile-name")
        compose.onNodeWithTag("profile-name").performTextReplacement("Александра 李明")
        reveal("profile-phone");compose.onNodeWithTag("profile-phone").performTextReplacement("8 999 123 45 67")
        reveal("profile-organization");compose.onNodeWithTag("profile-organization").performClick().performTextReplacement("Политех")
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(compose.activity.cacheDir,"t09-profile-ime.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard();reveal("profile-save");compose.onNodeWithTag("profile-save").performClick();await("profile-saved")
        val session=e.auth.currentSession();val contact=runBlocking {(e.events.observePublicEvent(FakeFixtures.OPEN).first() as PublicEventResult.Found).contact!!}
        assertEquals("Александра 李明",contact.name);assertEquals("+79991234567",contact.phone)
        compose.activityRule.scenario.recreate();await("profile-name");compose.onNodeWithTag("profile-name").assertTextEquals("Александра 李明")
        assertEquals(session,e.auth.currentSession());reveal("sign-out");compose.onNodeWithTag("sign-out").performClick();await("login-screen")
        assertTrue(runBlocking {e.auth.signIn("host@example.com",ru.eventpass.core.domain.auth.AuthSecret("DemoPass123!"))} is ru.eventpass.core.domain.auth.AuthResult.Success)
        await("root-Tickets");compose.onNodeWithTag("tab-Profile").performClick();await("profile-name")
        compose.onNodeWithTag("profile-name").assertTextEquals("")
    }
    @Test fun actualPdfAndXlsxUseControlledContentProviderAndPdfQrDecodes()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val e=FakeEventPassEnvironment(authPolicy=FakeAuthPolicy(passwordIterations=1000));e.scenarios.signInAs(FakeFixtures.ALEX)
        val event=FakeFixtures.events().single {it.id==FakeFixtures.OPEN}.copy(title="Научная конференция · "+"Длинное название события ".repeat(12),venue="Московский политех, аудитория "+"Блок ".repeat(20),capacity=null)
        e.scenarios.putEvent(event)
        val issued=e.registrations.submitAuthenticated(AuthenticatedRegistrationCommand(event.id,1,"Александра 李明","+79991234567",
            mapOf("organization" to Answer.Text("=SUM(1,2)"),"workshop" to Answer.Checkbox(true),"format" to Answer.SingleChoice("listener")),true)) as AuthenticatedRegistrationOutcome.Registered
        val pdf=(e.documents.prepare(DocumentRequest.TicketPdf(issued.ticketId)) as DocumentResult.Ready).snapshot as DocumentSnapshot.Ticket
        val files=AndroidDocumentFiles(context);val base="content://ru.eventpass.android.demo.test.documents/"
        assertNull(files.save(pdf,base+"ticket.pdf"){e.documents.validate(pdf)})
        assertEquals(DocumentError.WRITE_FAILED,files.save(pdf,base+"failure.pdf"){null})
        var checks=0
        assertEquals(DocumentError.SESSION_CHANGED,files.save(pdf,base+"cancelled.pdf"){if(++checks>=2) DocumentError.SESSION_CHANGED else null})
        var cancelledChecks=0
        try { files.save(pdf,base+"cancelled.pdf"){if(++cancelledChecks>=2) throw CancellationException("Fixture");null};fail() } catch(_: CancellationException) { }
        assertEquals(0,File(context.cacheDir,"eventpass-documents").listFiles()!!.size)
        context.contentResolver.openFileDescriptor(Uri.parse(base+"ticket.pdf"),"r")!!.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                var decoded: String?=null
                for(i in 0 until renderer.pageCount) renderer.openPage(i).use { page ->
                    val bitmap=Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    try { decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text } catch(_: NotFoundException) { }
                    bitmap.recycle()
                }
                assertEquals(pdf.ticket.qrPayload,decoded)
            }
        }
        repeat(24) {n ->
            val guest=e.registrations.submitGuest(GuestRegistrationCommand(event.id,1,"Гость $n","sample$n@example.com",answers=mapOf("organization" to Answer.Text(listOf("=1+1","+SUM(A1)","-1","@user")[n%4]),"workshop" to Answer.Checkbox(false)),consentAccepted=true)) as GuestRegistrationOutcome.Issued
            if(n==0) e.scenarios.setTicketStatus(guest.ticket.ticketId,TicketStatus.USED)
        }
        e.scenarios.signInAs(FakeFixtures.HOST)
        e.checkIns.checkIn(event.id,ru.eventpass.core.domain.checkin.TicketCredential.Qr(ru.eventpass.core.domain.checkin.QrToken(pdf.ticket.qrPayload)))
        val first=(e.documents.prepare(DocumentRequest.AttendanceXlsx(event.id)) as DocumentResult.Ready).snapshot as DocumentSnapshot.Attendance
        e.attendance.cancelRegistration(event.id,first.rows.first {it.status==TicketStatus.ACTIVE}.registration.id)
        val xlsx=(e.documents.prepare(first.request) as DocumentResult.Ready).snapshot as DocumentSnapshot.Attendance
        assertEquals(25,xlsx.rows.size);assertTrue(xlsx.rows.any {it.status==TicketStatus.CANCELLED})
        assertNull(files.save(xlsx,base+"attendees.xlsx"){e.documents.validate(xlsx)})
        e.scenarios.signInAs(FakeFixtures.ALEX)
        val empty=(e.documents.prepare(DocumentRequest.AttendanceXlsx(FakeFixtures.OWNED)) as DocumentResult.Ready).snapshot
        assertNull(files.save(empty,base+"empty.xlsx"){e.documents.validate(empty)})
    }
    @Test fun dashboardSafCancellationAndControlledActivityResultKeepExportOwner() {
        val e=compose.backend().environment();runBlocking {e.scenarios.reset()};compose.login("host@example.com")
        androidx.test.espresso.Espresso.closeSoftKeyboard();compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("dashboard-${FakeFixtures.MIXED.value}").performScrollTo().performClick();await("dashboard-query")
        compose.onNodeWithTag("dashboard-screen").performScrollToIndex(0)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithTag("document-export").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        // Flush Compose effects before polling the external activity (the test clock owns recomposition).
        compose.waitForIdle()
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        var pickerVisible=false
        while(!pickerVisible && android.os.SystemClock.uptimeMillis()<deadline) {
            val descriptor=instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities")
            val activities=descriptor.use {java.io.FileInputStream(it.fileDescriptor).bufferedReader().use {r->r.readText()}}
            pickerVisible=activities.lineSequence().any {it.contains("topResumedActivity=") && it.contains("documentsui")}
            if(!pickerVisible) android.os.SystemClock.sleep(100)
        }
        assertTrue("System DocumentsUI did not resume",pickerVisible)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        await("dashboard-query");compose.onNodeWithTag("document-saved").assertDoesNotExist()
        val result=android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
            android.content.Intent().setData(Uri.parse("content://ru.eventpass.android.demo.test.documents/flow.xlsx")))
        val filter=android.content.IntentFilter(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE);addDataType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
        val controlled=instrumentation.addMonitor(filter,result,true)
        try {
            compose.onNodeWithTag("document-export").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick();await("document-saved")
        } finally {instrumentation.removeMonitor(controlled)}
    }
}
