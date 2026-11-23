package ru.eventpass.app

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import ru.eventpass.app.navigation.AppLaunch
import ru.eventpass.app.navigation.ReportInitialContent
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
import ru.eventpass.core.designsystem.EventPassTheme

class AppLaunchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialReadReleasesLaunchCoverWithoutReplayingItOnLaterLoads() {
        val ready = mutableStateOf(false)
        compose.setContent {
            EventPassTheme {
                AppLaunch(sessionOnly = false) {
                    ReportInitialContent(ready.value)
                    if (ready.value) Text("Билеты готовы", Modifier.testTag("tickets-ready"))
                    else LoadingSkeleton(LoadingLayout.Tickets)
                }
            }
        }

        compose.onNodeWithTag("app-launch").assertIsDisplayed()
        compose.runOnIdle { ready.value = true }
        awaitLaunchExit()
        compose.onNodeWithTag("tickets-ready").assertIsDisplayed()

        compose.runOnIdle { ready.value = false }
        compose.onNodeWithTag("loading-Tickets").assertIsDisplayed()
        compose.onNodeWithTag("app-launch").assertDoesNotExist()
    }

    @Test fun delayedReadRevealsSkeleton() {
        compose.setContent {
            EventPassTheme {
                AppLaunch(sessionOnly = false) { LoadingSkeleton(LoadingLayout.Profile) }
            }
        }

        compose.onNodeWithTag("app-launch").assertIsDisplayed()
        awaitLaunchExit()
        compose.onNodeWithTag("loading-Profile").assertIsDisplayed()
    }

    private fun awaitLaunchExit() {
        compose.waitUntil(6_000) { compose.onAllNodesWithTag("app-launch").fetchSemanticsNodes().isEmpty() }
    }
}
