package ru.eventpass.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.app.feature.event.*
import ru.eventpass.core.designsystem.EventPassTheme
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.data.fake.product.FakeFixtures

class M02QuestionPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun allTenFormatsRenderMetadataAndTypedSelectionWithoutLosingStableIds() {
        val env=compose.backend().environment();runBlocking {env.scenarios.reset()}
        val original=runBlocking {env.events.observePublicEvent(FakeFixtures.OPEN).first()} as PublicEventResult.Found
        val options=listOf(RegistrationQuestion.Option("a","Option A","Hint A"),RegistrationQuestion.Option("b","Option B","Hint B"))
        val questions=TextFormat.entries.map {RegistrationQuestion.Text(it.name,"Type ${it.name}",false,it,"Placeholder ${it.name}","Help ${it.name}")}+
            listOf(RegistrationQuestion.SingleChoice("choice","Type choice",false,options),
                RegistrationQuestion.SingleChoice("select","Type select",false,options,ChoiceFormat.SELECT),
                RegistrationQuestion.MultipleChoice("multiple","Type multiple",false,options,"Help multiple","Placeholder multiple"),
                RegistrationQuestion.Checkbox("checkbox","Type checkbox",false,"Help checkbox"))
        var model=EventUiState(loading=false,data=original.copy(event=original.event.copy(questions=questions,priceType=PriceType.PAID)))
        compose.runOnUiThread {compose.activity.setContent {EventPassTheme {
            val state=androidx.compose.runtime.remember {androidx.compose.runtime.mutableStateOf(model)}
            EventScreen("fixture",state.value,{state.value=state.value.copy(form=it);model=state.value},{},{},{},{})
        }}}
        compose.onNodeWithText("Платное").assertExists()
        questions.forEach {q -> compose.onNodeWithText(q.title).performScrollTo().assertIsDisplayed()}
        compose.onNodeWithText("Help DATE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Placeholder multiple").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Type multiple").performScrollTo()
        // Choose only the checkbox under this question, not repeated labels in single-choice groups.
        compose.onAllNodesWithText("Option A").onLast().performScrollTo().performClick()
        compose.runOnIdle {assertEquals(Answer.MultipleChoice(listOf("a")),model.form.answers["multiple"])}
    }
}
