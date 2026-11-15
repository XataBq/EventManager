package ru.eventpass.core.domain.event

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.registration.RegistrationQuestion

class EventEditingPolicyTest {
    private val now = Instant.parse("2026-10-27T09:00:00Z")
    private val fields = EventFields("Событие", now.plusSeconds(3600), venue = "Зал", capacity = 3, description = "Описание")

    @Test fun incompleteDraftHasDifferentValidationFromPublication() {
        assertTrue(EventEditingPolicy.storageErrors(EventFields()).isEmpty())
        assertEquals(setOf("title", "venue", "description", "startsAt", "capacity"), EventEditingPolicy.publicationErrors(EventFields(), now))
        assertTrue(EventEditingPolicy.publicationErrors(fields.copy(capacity = 0), now).contains("capacity"))
        assertTrue(EventEditingPolicy.publicationErrors(fields.copy(zoneId = "Invalid/Zone"), now).contains("zoneId"))
    }

    @Test fun invalidChoiceOptionsAndIdsCannotBecomePublishedQuestions() {
        val choice = DraftQuestion("choice", "Вопрос", kind = QuestionKind.SINGLE_CHOICE)
        val a = RegistrationQuestion.Option("one", "Первый")
        val b = RegistrationQuestion.Option("two", "Второй")
        val invalid = listOf(
            listOf(choice),
            listOf(choice.copy(options = listOf(a, a))),
            listOf(choice.copy(options = listOf(a, b.copy(title = "")))),
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
            valid.zoneId, valid.venue, valid.capacity, EventLifecycle.PUBLISHED, true, 1, EventEditingPolicy.questions(valid), description = valid.description)
        assertEquals(valid, event.toFields())
    }

    @Test fun serverOpaqueIdsDuplicateLabelsAndEditingRegisteredQuestionsAreSupported() {
        val options = listOf(RegistrationQuestion.Option("../../opaque", "Same"), RegistrationQuestion.Option("другой", "Same"))
        val q = DraftQuestion("問題", "Вопрос", kind = QuestionKind.SELECT, options = options)
        assertTrue(EventEditingPolicy.publicationErrors(fields.copy(questions = listOf(q)), now).isEmpty())
        val event = Event(EventId("e"), UserId("u"), fields.title, fields.startsAt!!, null, fields.zoneId, fields.venue,
            fields.capacity, EventLifecycle.PUBLISHED, true, 1, emptyList(), description = fields.description)
        assertNull(EventEditingPolicy.updateError(event, fields.copy(questions = listOf(q)), now, 1, true))
    }

    @Test fun unicodeDescriptionUsesCodePointsAndAllWireFormatsRoundTrip() {
        val questions = QuestionKind.entries.filter { it != QuestionKind.UNSUPPORTED }.map { kind ->
            DraftQuestion(kind.name, kind.name, kind = kind, options = if (kind in setOf(QuestionKind.SINGLE_CHOICE, QuestionKind.SELECT, QuestionKind.MULTIPLE))
                listOf(RegistrationQuestion.Option("a", "A"), RegistrationQuestion.Option("b", "B")) else emptyList(), placeholder = "Hint", help = "Help")
        }
        val f = fields.copy(description = "😀".repeat(20000), questions = questions)
        assertTrue(EventEditingPolicy.publicationErrors(f, now).isEmpty())
        assertEquals(questions, EventEditingPolicy.questions(f).map { it.toDraftQuestion() })
        assertTrue(EventEditingPolicy.storageErrors(f.copy(description = f.description + "a")).contains("description"))
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
