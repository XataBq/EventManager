package ru.eventpass.core.domain.event

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.registration.RegistrationQuestion

class EventEditingPolicyTest {
    private val now = Instant.parse("2026-10-27T09:00:00Z")
    private val fields = EventFields("Событие", now.plusSeconds(3600), venue = "Зал")

    @Test fun incompleteDraftHasDifferentValidationFromPublication() {
        assertTrue(EventEditingPolicy.storageErrors(EventFields()).isEmpty())
        assertEquals(setOf("title", "venue", "startsAt"), EventEditingPolicy.publicationErrors(EventFields(), now))
        assertTrue(EventEditingPolicy.publicationErrors(fields.copy(capacity = 0), now).isEmpty())
        assertTrue(EventEditingPolicy.publicationErrors(fields.copy(zoneId = "Invalid/Zone"), now).contains("zoneId"))
    }

    @Test fun invalidChoiceOptionsAndIdsCannotBecomePublishedQuestions() {
        val choice = DraftQuestion("choice", "Вопрос", kind = QuestionKind.SINGLE_CHOICE)
        val a = RegistrationQuestion.Option("one", "Первый")
        val b = RegistrationQuestion.Option("two", "Второй")
        val invalid = listOf(
            listOf(choice),
            listOf(choice.copy(options = listOf(a, a))),
            listOf(choice.copy(options = listOf(a, b.copy(title = " первый ")))),
            listOf(choice.copy(options = listOf(a.copy(id = "../../path")))),
            listOf(choice.copy(options = listOf(a)), choice.copy(options = listOf(b))),
        )
        for (questions in invalid) {
            assertTrue(EventEditingPolicy.publicationErrors(fields.copy(questions = questions), now).contains("questions"))
        }
    }

    @Test fun conversionRetainsAllQuestionIdsRequiredOrderAndChoiceOptions() {
        val questions = listOf(DraftQuestion("q-text", "Текст", true),
            DraftQuestion("q-check", "Чекбокс", kind = QuestionKind.CHECKBOX),
            DraftQuestion("q-choice", "Выбор", true, QuestionKind.SINGLE_CHOICE,
                listOf(RegistrationQuestion.Option("b", "Второй"), RegistrationQuestion.Option("a", "Первый"))))
        val valid = fields.copy(questions = questions)
        assertTrue(EventEditingPolicy.publicationErrors(valid, now).isEmpty())
        val event = Event(EventId("one"), UserId("owner"), valid.title, valid.startsAt!!, valid.endsAt,
            valid.zoneId, valid.venue, valid.capacity, EventLifecycle.PUBLISHED, true, 1, EventEditingPolicy.questions(valid))
        assertEquals(valid, event.toFields())
    }

    @Test fun coverReferenceRejectsPathsUnsupportedMimeAndOversize() {
        for (factory in listOf<() -> EventCover>(
            { EventCover("../image", "image/png", 10) }, { EventCover("content://image", "image/png", 10) },
            { EventCover("image", "image/gif", 10) }, { EventCover("image", "image/jpeg", 5_242_881) },
            { EventCover("image", "image/png", 0) })) {
            assertTrue(runCatching(factory).exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(5_242_880L, EventCover("image", "image/jpeg", 5_242_880).byteCount)
    }
}
