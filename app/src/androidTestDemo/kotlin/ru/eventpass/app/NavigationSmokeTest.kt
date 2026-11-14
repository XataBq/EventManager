package ru.eventpass.app

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before fun signIn() { compose.login() }

    @Test fun hiltEntrySurvivesTabsAndRecreationButNotPop() {
        compose.onNodeWithTag("open-demo").performScrollTo().performClick()
        compose.onNodeWithTag("increment").performClick()
        compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 1")
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("root-Events").assertIsDisplayed()
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab-Profile").assertIsSelected()
        compose.onNodeWithTag("tab-Tickets").performClick()
        compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 1")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 1")
        compose.onNodeWithTag("close-demo").performClick()
        compose.onNodeWithTag("open-demo").performScrollTo().performClick()
        compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 0")
        compose.onNodeWithTag("tab-Tickets").performClick()
        compose.onNodeWithTag("root-Tickets").assertIsDisplayed()
        compose.onNodeWithTag("tab-Profile").performClick()
        pressSystemBack()
        compose.onNodeWithTag("root-Tickets").assertIsDisplayed()
    }

    @Test fun warmLinkIsIdempotentAndBackReturnsToTickets() {
        val activity = compose.activity
        val launchIntent = Intent(activity.intent)
        try {
            compose.onNodeWithTag("tab-Profile").performClick()
            sendLink("eventpass-demo://demo/demo")
            compose.onNodeWithTag("increment").performClick()
            sendLink("eventpass-demo://demo/demo")
            compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 1")
            pressSystemBack()
            compose.onNodeWithTag("root-Tickets").assertIsDisplayed()
            sendLink("eventpass-demo://demo/missing")
            compose.onNodeWithText("Демонстрационные данные не найдены.").assertIsDisplayed()
            compose.onNodeWithTag("back").performClick()
            sendLink("eventpass-demo://wrong/demo")
            compose.onNodeWithText("Неподдерживаемая демонстрационная ссылка.").assertIsDisplayed()
        } finally {
            // ActivityScenario 1.7 filters lifecycle events by its original launch Intent.
            // Restore it only for test teardown after exercising the real onNewIntent path.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = launchIntent }
        }
    }

    @Test fun galleryBackAndTabSwitchKeepTheExistingDemoViewModel() {
        compose.onNodeWithTag("open-demo").performScrollTo().performClick()
        compose.onNodeWithTag("increment").performClick()
        compose.onNodeWithTag("open-gallery").performScrollTo().performClick()
        compose.onNodeWithTag("component-gallery").assertIsDisplayed()
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.onNodeWithTag("tab-Tickets").performClick()
        compose.onNodeWithTag("component-gallery").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("component-gallery").assertIsDisplayed()
        pressSystemBack()
        compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 1")
    }

    @Test fun galleryControlsExposeCheckedSelectedAndEditableStates() {
        compose.onNodeWithTag("open-gallery").performScrollTo().performClick()
        compose.onNodeWithText("Выбор").performScrollTo().performClick()
        compose.onNodeWithTag("gallery-consent").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("gallery-switch").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("gallery-option-two").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Поля").performScrollTo().performClick()
        compose.onNodeWithTag("gallery-name").performScrollTo().performTextInput("Мария")
        compose.onNodeWithTag("gallery-name").assertTextEquals("Мария")
        compose.onNodeWithContentDescription("Показать пароль").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Скрыть пароль").assertIsDisplayed()
        // Finish with the keyboard closed; Back remains an app navigation action in the other smoke test.
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun sendLink(uri: String) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitForIdle()
    }

    private fun pressSystemBack() {
        // Activity 1.13/Nav3 use NavigationEventDispatcher; exercise real input, not the legacy dispatcher.
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
}
