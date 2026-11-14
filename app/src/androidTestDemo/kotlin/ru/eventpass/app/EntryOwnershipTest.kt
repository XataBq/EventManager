package ru.eventpass.app

import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.eventpass.app.feature.demo.DemoViewModel
import ru.eventpass.app.navigation.DemoDetails
import ru.eventpass.app.navigation.RootTab
import ru.eventpass.core.designsystem.EventPassTheme

/** Uses the real Hilt factory and the same decorator ordering as the app. */
@RunWith(AndroidJUnit4::class)
class EntryOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun removingEntryClearsItsViewModelAndANewEntryGetsANewInstance() {
        var current: DemoViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                EventPassTheme {
                    val stack = rememberNavBackStack(RootTab.Tickets)
                    NavDisplay(
                        backStack = stack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            entry<RootTab> {
                                Button(onClick = { stack.add(DemoDetails("demo")) }) { Text("Open") }
                            }
                            entry<DemoDetails> { route ->
                                val vm = hiltViewModel<DemoViewModel, DemoViewModel.Factory>(
                                    creationCallback = { it.create(route.recordId) },
                                )
                                SideEffect { current = vm }
                                Button(onClick = { stack.removeLastOrNull() }) { Text("Close") }
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Open").performClick()
        lateinit var first: DemoViewModel
        lateinit var job: Job
        compose.runOnIdle {
            first = requireNotNull(current)
            job = requireNotNull(first.viewModelScope.coroutineContext[Job])
            assertFalse(job.isCancelled)
        }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(job.isCancelled) }
        compose.onNodeWithText("Open").performClick()
        compose.runOnIdle { assertNotSame(first, current) }
    }
}
