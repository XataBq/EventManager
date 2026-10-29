package ru.eventpass.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.app.di.DemoBackendEntryPoint
import ru.eventpass.core.domain.auth.*

/** No ActivityScenario launch: exercises onCreate(ACTION_VIEW), not onNewIntent. */
class AuthColdActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun coldActivityConfirmsEmailWithoutAcceptingSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val backend = EntryPointAccessors.fromApplication(context, DemoBackendEntryPoint::class.java)
        val email = "cold-${System.nanoTime()}@example.com"
        runBlocking {
            backend.auth().signOut()
            backend.auth().signUp("Проверка", email, AuthSecret("ColdPass123!"))
        }
        val mail = backend.environment().scenarios.authOutbox().last()
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_VIEW,
            Uri.parse("eventpass-demo://demo/confirm/${mail.challenge.id}?token=${mail.secret.value}"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            compose.onNodeWithText("Email подтверждён. Войдите вручную.").assertIsDisplayed()
            assertEquals(SessionState.Guest, backend.auth().currentSession().state)
            assertNull(activity.intent.data)
            compose.onNodeWithTag("tab-Tickets").assertDoesNotExist()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
