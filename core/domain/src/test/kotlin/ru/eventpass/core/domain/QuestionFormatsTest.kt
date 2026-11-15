package ru.eventpass.core.domain

import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.registration.*

class QuestionFormatsTest {
    private fun validate(q: List<RegistrationQuestion>, answers: Map<String, Answer>) = RegistrationValidator.validate(
        GuestRegistrationCommand(EventId("event"), null, "李", "user@example.com", answers = answers, consentAccepted = true), q, RegistrationPolicy())
    private val options = listOf(RegistrationQuestion.Option("a", "A"), RegistrationQuestion.Option("b", "B"))

    @Test fun allTenFormatsKeepTypedAnswersAndOptionalFalse() {
        val q = TextFormat.entries.map { RegistrationQuestion.Text(it.name, it.name, true, it) } + listOf(
            RegistrationQuestion.SingleChoice("choice", "Choice", true, options),
            RegistrationQuestion.SingleChoice("select", "Select", true, options, ChoiceFormat.SELECT),
            RegistrationQuestion.MultipleChoice("multiple", "Many", true, options), RegistrationQuestion.Checkbox("bool", "Flag", false))
        val answers = mapOf("TEXT" to Answer.Text(" hello "), "TEXTAREA" to Answer.Text("First\nSecond"),
            "EMAIL" to Answer.Text("test@example.com"), "TEL" to Answer.Text("+49 (123) 4567890"),
            "NUMBER" to Answer.Text("-1.25e2"), "DATE" to Answer.Text("2028-02-29"),
            "choice" to Answer.SingleChoice("a"), "select" to Answer.SingleChoice("b"),
            "multiple" to Answer.MultipleChoice(listOf("b", "a")), "bool" to Answer.Checkbox(false))
        val result = validate(q, answers) as RegistrationValidation.Valid
        assertEquals(10, result.value.answers.size); assertEquals(Answer.Checkbox(false), result.value.answers["bool"])
        assertEquals(Answer.Text("hello"), result.value.answers["TEXT"])
        assertEquals(Answer.MultipleChoice(listOf("b", "a")), result.value.answers["multiple"])
    }
    @Test fun invalidTextSubtypesRejectUnsafeOrNonCanonicalValues() {
        val cases = mapOf(TextFormat.EMAIL to listOf("bad"), TextFormat.TEL to listOf("12", "call-me"),
            TextFormat.NUMBER to listOf("NaN", "Infinity", "1e999", "1,25"),
            TextFormat.DATE to listOf("2026-02-29", "2026-2-01", "2026-01-01T00:00:00Z"))
        cases.forEach { (format, values) -> values.forEach { value ->
            assertTrue("$format rejects $value", validate(listOf(RegistrationQuestion.Text("q", "Q", true, format)), mapOf("q" to Answer.Text(value))) is RegistrationValidation.Invalid)
        } }
    }
    @Test fun multipleRequiresKnownUniqueValuesAndCorrectType() {
        val q = listOf(RegistrationQuestion.MultipleChoice("q", "Q", true, options))
        for (answer in listOf(Answer.MultipleChoice(emptyList()), Answer.MultipleChoice(listOf("a", "a")),
            Answer.MultipleChoice(listOf("unknown")), Answer.SingleChoice("a"))) {
            assertTrue(validate(q, mapOf("q" to answer)) is RegistrationValidation.Invalid)
        }
        val optional = listOf(RegistrationQuestion.MultipleChoice("q", "Q", false, options))
        assertTrue(validate(optional, mapOf("q" to Answer.MultipleChoice(emptyList()))) is RegistrationValidation.Valid)
    }
    @Test fun unsupportedEvenOptionalCannotSilentlySubmitAndUnknownAnswersStayErrors() {
        assertTrue(validate(listOf(RegistrationQuestion.Unsupported("q", "New", false, "future")), emptyMap()) is RegistrationValidation.Invalid)
        val result = validate(emptyList(), mapOf("unknown" to Answer.Text("answer"))) as RegistrationValidation.Invalid
        assertTrue(result.errors.any { it.issue == FieldIssue.UNKNOWN_QUESTION })
    }
    @Test fun textBoundCountsUnicodeCodePointsAndRequiredCheckboxRequiresTrue() {
        val q = listOf(RegistrationQuestion.Text("q", "Q", false))
        assertTrue(validate(q, mapOf("q" to Answer.Text("😀".repeat(4000)))) is RegistrationValidation.Valid)
        assertTrue(validate(q, mapOf("q" to Answer.Text("😀".repeat(4001)))) is RegistrationValidation.Invalid)
        val required = listOf(RegistrationQuestion.Checkbox("q", "Accept", true))
        assertTrue(validate(required, mapOf("q" to Answer.Checkbox(false))) is RegistrationValidation.Invalid)
        assertTrue(validate(required, mapOf("q" to Answer.Checkbox(true))) is RegistrationValidation.Valid)
    }
}
