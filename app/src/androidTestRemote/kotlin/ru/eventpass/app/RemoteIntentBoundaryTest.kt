package ru.eventpass.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class RemoteIntentBoundaryTest {
 @Test fun ignoredSecretUriIsRemovedFromLaunchAndNewIntent() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val delivery=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN)
  var initial: Intent? = null
  ActivityScenario.launch<MainActivity>(delivery).use { scenario -> scenario.onActivity { activity ->
   initial=activity.intent
   assertNull(activity.intent.data); assertEquals(Intent.ACTION_MAIN,activity.intent.action)
   val next=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("eventpass-demo://demo/confirm/invalid?token=secret-fixture")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
   activity.startActivity(next)
  }
   InstrumentationRegistry.getInstrumentation().waitForIdleSync()
   scenario.onActivity { activity -> assertNotSame(initial,activity.intent); assertNull(activity.intent.data); assertNull(activity.intent.extras); assertEquals(Intent.ACTION_MAIN,activity.intent.action) }
  }
 }
}
