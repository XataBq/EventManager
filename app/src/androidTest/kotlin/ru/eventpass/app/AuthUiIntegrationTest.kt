package ru.eventpass.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.eventpass.app.navigation.EventPassApp
import ru.eventpass.app.navigation.RootTab
import ru.eventpass.app.navigation.auth.AppSessionViewModel
import ru.eventpass.core.designsystem.EventPassTheme
import ru.eventpass.core.domain.auth.SessionState

class RootOwnerProbe : ViewModel()

@RunWith(AndroidJUnit4::class)
class AuthUiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun loginLogoutClearsRootAndHiddenOwnersAndNewAccountGetsNewInstances() {
        val roots = mutableMapOf<RootTab, RootOwnerProbe>()
        compose.activityRule.scenario.onActivity { activity ->
            val host = ViewModelProvider(activity)[AppSessionViewModel::class.java]
            activity.setContent {
                EventPassTheme { EventPassApp(host) { tab ->
                    val probe: RootOwnerProbe = viewModel()
                    SideEffect { roots[tab] = probe }
                } }
            }
        }
        compose.login()
        compose.onNodeWithTag("open-demo").performScrollTo().performClick()
        compose.onNodeWithTag("increment").performClick()
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("root-Events").assertIsDisplayed()
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.onNodeWithTag("root-Profile").assertIsDisplayed()
        lateinit var old: Map<RootTab, RootOwnerProbe>
        compose.runOnIdle { old = roots.toMap() }
        assertEquals(3, old.size)
        val jobs = old.values.map { requireNotNull(it.viewModelScope.coroutineContext[Job]) }
        compose.onNodeWithTag("tab-Tickets").performClick()
        compose.onNodeWithTag("demo-count").assertTextEquals("Счётчик: 1")
        compose.runOnIdle { jobs.forEach { assertFalse(it.isCancelled) } }
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.onNodeWithTag("sign-out").performScrollTo().performClick()
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.runOnIdle { jobs.forEach { assertTrue(it.isCancelled) } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab-Tickets").assertDoesNotExist()
        // Recreation restores the production content; reinstall only the test probe callback.
        compose.activityRule.scenario.onActivity { activity ->
            val host = ViewModelProvider(activity)[AppSessionViewModel::class.java]
            activity.setContent { EventPassTheme { EventPassApp(host) { tab ->
                val probe: RootOwnerProbe = viewModel()
                SideEffect { roots[tab] = probe }
            } } }
        }
        compose.login("host@example.com", prepareGuest = false)
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("root-Events").assertIsDisplayed()
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.onNodeWithTag("profile-email").assertTextEquals("host@example.com")
        compose.runOnIdle { old.forEach { (tab, probe) -> assertNotSame(probe, roots[tab]) } }
        compose.onNodeWithTag("tab-Tickets").performClick()
        compose.onNodeWithTag("demo-count").assertDoesNotExist()
    }

    @Test fun confirmationDeliverySurvivesRotationAndRequiresManualLogin() {
        val e = compose.backend().environment()
        val email = "ui-${System.nanoTime()}@example.com"
        runBlocking {
            e.auth.signOut()
            e.auth.signUp("Проверка", email, ru.eventpass.core.domain.auth.AuthSecret("NewDemoPass123!"))
        }
        val mail = e.scenarios.authOutbox().last()
        runBlocking { e.scenarios.failNextAuth(ru.eventpass.core.domain.auth.AuthError.Network) }
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("eventpass-demo://demo/confirm/${mail.challenge.id}?token=${mail.secret.value}"), activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Ошибка связи. Повторите запрос.").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Повторить подтверждение").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Email подтверждён. Войдите вручную.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Email подтверждён. Войдите вручную.").assertIsDisplayed()
        assertEquals(SessionState.Guest, e.auth.currentSession().state)
        assertNull(compose.activity.intent.data)
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithTag("auth-email").assertTextEquals(email)
        compose.login(email, "NewDemoPass123!", prepareGuest = false)
    }

    @Test fun eventReturnSurvivesRecreationAndExpiryRemovesPrivateShell() {
        runBlocking { compose.backend().auth().signOut() }
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("eventpass-demo://demo/e/open-registration"), activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.onNodeWithTag("event-open-registration").assertIsDisplayed()
        compose.onNodeWithTag("event-login").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        // This login intentionally expects E instead of the Tickets root.
        compose.onNodeWithTag("auth-email").performTextReplacement("alex@example.com")
        compose.onNodeWithTag("auth-password").performTextReplacement("DemoPass123!")
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("event-open-registration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("event-open-registration").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("event-open-registration").assertIsDisplayed()
        compose.backend().clock().advance(java.time.Duration.ofMinutes(31))
        // Foreground refresh must detect expiry even when its previous timer was suspended.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithTag("tab-Tickets").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("event-open-registration").assertDoesNotExist()
    }

    @Test fun signupOutboxConfirmationManualLoginAndPasswordResetAreReachable() {
        val email = "story-${System.nanoTime()}@example.com"
        runBlocking { compose.backend().auth().signOut() }
        compose.onNodeWithText("Создать аккаунт").performScrollTo().performClick()
        compose.onNodeWithTag("auth-name").performTextInput("Мария 李")
        compose.onNodeWithTag("auth-email").performTextInput(email)
        compose.onNodeWithTag("auth-password").performScrollTo().performTextInput("OriginalPass123!")
        compose.onNodeWithTag("auth-consent").performScrollTo().performClick()
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Отправить повторно").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SessionState.Guest, compose.backend().auth().currentSession().state)
        compose.onNodeWithText("Имитация почты").performScrollTo().performClick()
        compose.onAllNodesWithText("Открыть подтверждение").onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Email подтверждён. Войдите вручную.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Понятно").performClick()
        compose.login(email, "OriginalPass123!", prepareGuest = false)
        compose.onNodeWithTag("tab-Profile").performClick()
        compose.onNodeWithTag("sign-out").performScrollTo().performClick()
        compose.onNodeWithText("Забыли пароль?").performScrollTo().performClick()
        compose.onNodeWithTag("auth-email").performTextReplacement(email)
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("auth-code").fetchSemanticsNodes().isNotEmpty() }
        val code = compose.backend().environment().scenarios.authOutbox().last().secret.value
        compose.onNodeWithTag("auth-code").performTextInput(code)
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("auth-password").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth-password").performTextInput("ReplacementPass123!")
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Пароль изменён. Войдите вручную с новым паролем.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SessionState.Guest, compose.backend().auth().currentSession().state)
        compose.onNodeWithText("Войти").performClick()
        compose.login(email, "ReplacementPass123!", prepareGuest = false)
    }

    @Test fun eventScopedGuardRejectsForbiddenWithoutLoginLoop() {
        compose.login("host@example.com")
        compose.onNodeWithText("Демо-инструменты").performScrollTo().performClick()
        compose.onNodeWithText("Проверить доступ к сканированию E «alex-owned»").performScrollTo().performClick()
        compose.onNodeWithText("Нет доступа к этому действию мероприятия.").assertIsDisplayed()
        compose.onNodeWithText("Понятно").performClick()
        assertTrue(compose.backend().auth().currentSession().state is SessionState.Authenticated)
        compose.onNodeWithTag("tab-Tickets").assertIsDisplayed()
        runBlocking { compose.backend().auth().signOut() }
        compose.onNodeWithText("Демо-инструменты").performScrollTo().performClick()
        compose.onNodeWithText("Проверить доступ к сканированию E «alex-owned»").performScrollTo().performClick()
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithText("После входа вы вернётесь к мероприятию. Анкету нужно заполнить заново.").assertIsDisplayed()
    }
}
