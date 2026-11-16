package ru.eventpass.app

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class M02EventRecreationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun sameEntryRetainsUnsubmittedContactAndConsentAcrossActivityRecreation() {
        runBlocking {compose.backend().environment().scenarios.reset()}
        compose.waitUntil(10_000){compose.onAllNodesWithTag("login-screen").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnUiThread { ViewModelProvider(compose.activity)[ru.eventpass.app.navigation.auth.AppSessionViewModel::class.java]
            .receiveLink("eventpass-demo://demo/e/open-registration") }
        compose.waitUntil(10_000){compose.onAllNodesWithTag("registration-name").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("registration-name").performScrollTo().performTextReplacement("Local name")
        compose.onNodeWithTag("registration-email").performScrollTo().performTextReplacement("local@example.com")
        compose.onNodeWithTag("registration-consent").performScrollTo().performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("registration-name").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("registration-name").assertTextEquals("Local name")
        compose.onNodeWithTag("registration-email").assertTextEquals("local@example.com")
        compose.onNodeWithTag("registration-consent").assertIsOn()
    }
}
