package ru.eventpass.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.core.designsystem.*

/** First slice: real nested scrolling, short/error content and semantic fallback. */
class PullRefreshComponentTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun topPullRefreshesOnceMiddleScrollDoesNotAndDisabledOverlayBlocksAction() {
        var calls=0; var refreshing by mutableStateOf(false); var enabled by mutableStateOf(true)
        compose.runOnUiThread { compose.activity.setContent { EventPassTheme {
            EventPassRefresh(refreshing,{calls++;refreshing=true},Modifier.testTag("refresh"),enabled) {
                LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                    items(60) { Text("Row $it",Modifier.fillMaxWidth().height(64.dp)) }
                }
            }
        } } }
        compose.onNodeWithTag("list").performScrollToIndex(30)
        compose.onNodeWithTag("list").performTouchInput {swipeDown()}
        compose.runOnIdle {assertEquals(0,calls)}
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.onNodeWithTag("list").performTouchInput {swipeDown()}
        compose.runOnIdle {assertEquals(1,calls)}
        compose.onNodeWithTag("refresh").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Обновление данных"))
        compose.onNodeWithTag("list").performTouchInput {swipeDown()}
        compose.runOnIdle {assertEquals(1,calls);refreshing=false;enabled=false}
        compose.onNodeWithTag("list").performTouchInput {swipeDown()}
        compose.onNodeWithTag("refresh").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        compose.runOnIdle {assertEquals(1,calls)}
    }
    @Test fun shortEmptyAndErrorContentAcceptSwipeAndSemanticRetry() {
        var calls=0; var label by mutableStateOf("Пусто")
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {
            EventPassRefresh(false,{calls++},Modifier.testTag("refresh")) {
                ru.eventpass.app.feature.auth.AuthColumn(Modifier.testTag("short")) { Text(label) }
            }
        }}}
        compose.onNodeWithTag("short").performTouchInput {swipeDown()}
        compose.runOnIdle {assertEquals(1,calls);label="Ошибка сети"}
        compose.onNodeWithTag("short").performTouchInput {swipeDown()}
        compose.runOnIdle {assertEquals(2,calls)}
        val actions=compose.onNodeWithTag("refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {assertTrue(actions.single {it.label=="Обновить данные"}.action())}
        compose.runOnIdle {assertEquals(3,calls)}
    }
}
