package ru.eventpass.app

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.platform.AndroidSessionTokenStore
import ru.eventpass.data.remote.*

/** Real remote DI, HTTPS staging, Keystore, Compose navigation and Android DocumentsUI. */
class RemoteMvpDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private fun await(tag:String) {try {compose.waitUntil(30_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}} catch(ex:Throwable) {throw AssertionError("Expected UI tag $tag",ex)}}
    private fun click(tag:String) {await(tag);val node=compose.onNodeWithTag(tag);if(!tag.startsWith("tab-")) node.performScrollTo();node.performClick();compose.waitForIdle()}
    private fun shell(command:String)=automation.executeShellCommand(command).use {FileInputStream(it.fileDescriptor).bufferedReader().readText()}
    private fun back(){shell("input keyevent 4");compose.waitForIdle()}
    private fun hideIme(scenario:ActivityScenario<MainActivity>) {
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            var visible=false
            scenario.onActivity {activity->
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.currentFocus?.windowToken ?: activity.window.decorView.windowToken,0)
                visible=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
            }
            !visible && automation.windows.none {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser==true}
        }
        automation.waitForIdle(300,5000)
    }
    private fun find(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        if(node==null) return null
        if(predicate(node)) return node
        for(index in 0 until node.childCount) find(node.getChild(index),predicate)?.let {return it}
        return null
    }
    private fun awaitPicker():AccessibilityNodeInfo {
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val deadline=SystemClock.uptimeMillis()+20_000
        var save:AccessibilityNodeInfo?=null
        while(save==null && SystemClock.uptimeMillis()<deadline) {
            save=find(documentRoot()){it.packageName?.toString()?.contains("documentsui")==true &&
                (it.text?.toString()?.lowercase() in setOf("save","сохранить")) && it.isEnabled}
            if(save==null) SystemClock.sleep(100)
        }
        if(save==null) {
            val file=java.io.File(instrumentation.targetContext.filesDir,"m09-picker-failure.png")
            automation.takeScreenshot()?.let {bitmap->file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
            throw AssertionError("Real DocumentsUI Save action unavailable; active package=${automation.rootInActiveWindow?.packageName}")
        }
        return requireNotNull(save)
    }
    private fun documentRoot():AccessibilityNodeInfo? = automation.windows.asSequence().mapNotNull {it.root}
        .firstOrNull {it.packageName?.toString()?.contains("documentsui")==true}
        ?: automation.rootInActiveWindow
    private fun pickerSave(name:String,rotate:Boolean) {
        click("document-export")
        var save:AccessibilityNodeInfo?=awaitPicker()
        // A unique name avoids an overwrite dialog; change the actual provider's filename field.
        val field=find(documentRoot()){it.isEditable}
        assertNotNull(field)
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)}))
        if(rotate) {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            val deadline=SystemClock.uptimeMillis()+10_000
            while(instrumentation.targetContext.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100)
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,instrumentation.targetContext.resources.configuration.orientation)
            automation.waitForIdle(1000,10_000);awaitPicker()
            // Reacquire the provider's recreated window. Its old node can acknowledge a click
            // without delivering a result while the rotation transaction is still running.
            val rotatedField=find(documentRoot()){it.isEditable}
            assertNotNull(rotatedField)
            assertTrue(rotatedField!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)}))
            automation.waitForIdle(500,10_000)
        }
        save=find(documentRoot()){it.text?.toString()?.lowercase() in setOf("save","сохранить") && it.isEnabled && it.isClickable}
        assertTrue(save!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));await("document-saved")
    }
    @Test fun remoteTabsBackManualScannerSafRotationAndProfileRecreation()=runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("m09Staging")=="1")
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=instrumentation.targetContext
        shell("pm revoke ${context.packageName} android.permission.CAMERA")
        shell("pm clear-permission-flags ${context.packageName} android.permission.CAMERA user-set user-fixed")
        val api=AuthHttp("https://staging.eventpass.dikanskii.ru/api/")
        var token:AuthSecret?=null
        val auth=RemoteAuthRepository(api,object:SessionTokenStore {override suspend fun read()=token;override suspend fun write(value:AuthSecret){token=value};override suspend fun clear(){token=null}}) {account->ru.eventpass.data.local.AndroidDraftStoreFactory.open(context).purge(account)}
        val existingName=InstrumentationRegistry.getArguments().getString("m09ExistingFixture")
        require(existingName==null || existingName=="m09-process-fixture.private.json")
        val existing=existingName?.let {Json.parseToJsonElement(java.io.File(context.noBackupFilesDir,it).readText()).jsonObject}
        val email=existing?.getValue("email")?.jsonPrimitive?.content ?: "android.m09.device.${UUID.randomUUID()}@example.com"
        require(email.startsWith("android.m09.") && email.endsWith("@example.com"))
        val password=AuthSecret(existing?.getValue("password")?.jsonPrimitive?.content ?: UUID.randomUUID().toString())
        val record=java.io.File(context.noBackupFilesDir,"m09-ui-fixture.private.json")
        check(!record.exists()) {"Clean the previous owned UI fixture before starting another"}
        record.writeText(buildJsonObject {put("email",email);put("password",password.value)}.toString())
        val http=OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
        fun request(path:String,body:String?=null,method:String?=null):String {
            val builder=Request.Builder().url("https://staging.eventpass.dikanskii.ru/api/$path").header("Authorization","Bearer "+requireNotNull(token).value)
            if(body!=null) builder.post(body.toRequestBody("application/json".toMediaType()))
            if(method!=null) builder.method(method,null)
            return http.newCall(builder.build()).execute().use {check(it.isSuccessful){"Synthetic device HTTP ${it.code}"};it.body.string()}
        }
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            assertTrue((if(existing==null) auth.signUp("M09 device",email,password,true) else auth.signIn(email,password)) is AuthResult.Success)
            val event=Json.parseToJsonElement(request("events",buildJsonObject {put("title","M09 device ${UUID.randomUUID()}");put("description","Synthetic device fixture");put("location","Staging");put("startsAt",Instant.now().minusSeconds(3600).toString());put("capacity",5);put("publish",true);put("questions",buildJsonArray {})}.toString())).jsonObject
            val id=EventId(event.getValue("id").jsonPrimitive.content)
            val issued=RemoteRegistrations(api,auth).submitAuthenticated(AuthenticatedRegistrationCommand(id,null,"ignored",consentAccepted=true)) as AuthenticatedRegistrationOutcome.Registered
            AndroidSessionTokenStore(context).write(requireNotNull(token))
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            await("root-Tickets");click("ticket-${issued.ticketId.value}");await("selected-ticket")
            back();compose.onNodeWithTag("selected-ticket").assertDoesNotExist()
            click("ticket-${issued.ticketId.value}")
            // Cancel/reopen must leave a new usable picker. Its real result survives rotation.
            click("document-export");awaitPicker();back()
            pickerSave("m09-${UUID.randomUUID()}.pdf",rotate=true)
            back();click("tab-Events");click("dashboard-${id.value}");await("dashboard-totals")
            compose.onNodeWithTag("dashboard-screen").performScrollToNode(hasTestTag("dashboard-query"));await("dashboard-query")
            pickerSave("m09-${UUID.randomUUID()}.xlsx",rotate=false)
            // Return to the event list, then use the common scanner without camera permission.
            back();click("scan-${id.value}");await("scan-input")
            click("scan-camera")
            val permissionDeadline=SystemClock.uptimeMillis()+15_000
            var deny:AccessibilityNodeInfo?=null
            while(deny==null && SystemClock.uptimeMillis()<permissionDeadline) {
                deny=find(automation.rootInActiveWindow){it.viewIdResourceName?.endsWith("/permission_deny_button")==true}
                if(deny==null) SystemClock.sleep(100)
            }
            assertNotNull("Camera permission dialog",deny);assertTrue(deny!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitForIdle();assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.CAMERA))
            compose.onNodeWithTag("scan-input").performScrollTo().performTextInput("EVT-XXXXXX")
            hideIme(scenario)
            click("scan-submit");await("scan-result");hideIme(scenario);back();await("scan-input");back();await("root-Events")
            click("tab-Profile");await("profile-name")
            compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement("M09 retained edit")
            compose.waitForIdle();compose.onNodeWithTag("profile-name").assertTextContains("M09 retained edit")
            hideIme(scenario)
            scenario.recreate();await("profile-name")
            compose.onNodeWithTag("profile-name").assertTextContains("M09 retained edit")
            click("profile-save");await("profile-saved")
            click("sign-out");await("login-screen")
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            scenario?.close()
            if(token!=null) {
                assertTrue(auth.signIn(email,password) is AuthResult.Success)
                assertTrue(auth.profileRepository.deleteAccount() is ru.eventpass.core.domain.profile.DeleteAccountResult.Deleted)
                check(record.delete())
                existingName?.let {check(java.io.File(context.noBackupFilesDir,it).delete())}
            }
            AndroidSessionTokenStore(context).clear()
        }
    }
}
