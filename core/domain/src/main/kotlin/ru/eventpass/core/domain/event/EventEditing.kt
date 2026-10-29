package ru.eventpass.core.domain.event

import java.time.Instant
import java.time.ZoneId
import ru.eventpass.core.domain.registration.RegistrationQuestion

@JvmInline value class DraftId(val value: String)
enum class QuestionKind { TEXT, CHECKBOX, SINGLE_CHOICE }

/** Incomplete editor input, including a choice with no options yet. IDs survive every save. */
data class DraftQuestion(
    val id: String,
    val title: String = "",
    val required: Boolean = false,
    val kind: QuestionKind = QuestionKind.TEXT,
    val options: List<RegistrationQuestion.Option> = emptyList(),
)

/** Opaque app-owned asset ID, never an arbitrary file path/URI or image bytes. */
data class EventCover(val assetId: String, val mimeType: String, val byteCount: Long) {
    init {
        require(assetId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(mimeType in setOf("image/jpeg", "image/png"))
        require(byteCount in 1..5_242_880L)
    }
}

data class EventFields(
    val title: String = "",
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val zoneId: String = "Europe/Moscow",
    val venue: String = "",
    val capacity: Int? = null,
    val questions: List<DraftQuestion> = emptyList(),
    val description: String = "",
    val sections: List<EventSection> = emptyList(),
    val cover: EventCover? = null,
)

data class EventDraft(
    val id: DraftId, val ownerId: UserId, val revision: Long, val fields: EventFields,
    /** Retained privately as the idempotent publication receipt; excluded from the drafts list. */
    val publishedEventId: EventId? = null,
)

sealed interface EventEditorTarget {
    data class Draft(val id: DraftId) : EventEditorTarget
    data class Published(val id: EventId) : EventEditorTarget
}
sealed interface EventEditorResult {
    data class Draft(val draft: EventDraft) : EventEditorResult
    data class Published(val event: Event, val questionsLocked: Boolean, val now: Instant) : EventEditorResult
    data class Failed(val error: EventEditError) : EventEditorResult
}
sealed interface MyEventsResult {
    data class Ready(val organizing: List<Event>, val volunteering: List<Event>, val drafts: List<EventDraft>, val now: Instant) : MyEventsResult
    data class Failed(val error: EventEditError) : MyEventsResult
}
sealed interface EventEditResult<out T> {
    data class Saved<T>(val value: T) : EventEditResult<T>
    data class Failed(val error: EventEditError) : EventEditResult<Nothing>
}
sealed interface EventEditError {
    data class Validation(val fields: Set<String>) : EventEditError
    data object Conflict : EventEditError
    data object StaleForm : EventEditError
    data object QuestionsLocked : EventEditError
    data object EventFinished : EventEditError
    data object SessionChanged : EventEditError
    data object AuthenticationRequired : EventEditError
    data object Forbidden : EventEditError
    data object NotFound : EventEditError
    data object Network : EventEditError
    data object Timeout : EventEditError
    data object StorageUnavailable : EventEditError
}

fun Event.toFields() = EventFields(title, startsAt, endsAt, zoneId, venue, capacity, questions.map { q ->
    when (q) {
        is RegistrationQuestion.Text -> DraftQuestion(q.id, q.title, q.required, QuestionKind.TEXT)
        is RegistrationQuestion.Checkbox -> DraftQuestion(q.id, q.title, q.required, QuestionKind.CHECKBOX)
        is RegistrationQuestion.SingleChoice -> DraftQuestion(q.id, q.title, q.required, QuestionKind.SINGLE_CHOICE, q.options)
    }
}, description, sections, cover)

/** Central, reversible demo policy. Finished/cancelled is terminal; started dates cannot move. */
object EventEditingPolicy {
    private val id = Regex("[a-zA-Z0-9_-]{1,64}")
    fun validId(value: String) = id.matches(value)

    // Bound the binary snapshot's UTF fields/collections even for incomplete drafts.
    fun storageErrors(f: EventFields): Set<String> = buildSet {
        if (f.title.length > 200) add("title")
        if (f.venue.length > 1000) add("venue")
        if (f.zoneId.length > 100) add("zoneId")
        if (f.description.length > 10_000) add("description")
        if (f.sections.size > 50 || f.sections.any { it.title.length > 1000 || it.time.length > 1000 }) add("sections")
        if (f.questions.size > 100 || f.questions.any { q ->
            !validId(q.id) || q.title.length > 500 || q.options.size > 100 || q.options.any { !validId(it.id) || it.title.length > 300 }
        } || f.questions.map { it.id }.distinct().size != f.questions.size) add("questions")
    }

    fun publicationErrors(f: EventFields, now: Instant, allowStarted: Boolean = false): Set<String> = buildSet {
        addAll(storageErrors(f))
        if (f.title.isBlank() || f.title.any(Char::isISOControl)) add("title")
        if (f.venue.isBlank() || f.venue.any(Char::isISOControl)) add("venue")
        if (f.startsAt == null || (!allowStarted && f.startsAt <= now)) add("startsAt")
        if (f.endsAt != null && (f.startsAt == null || f.endsAt <= f.startsAt)) add("endsAt")
        if (runCatching { ZoneId.of(f.zoneId) }.isFailure) add("zoneId")
        if (f.capacity != null && f.capacity < 0) add("capacity")
        if (f.questions.any { q ->
            q.title.isBlank() || q.title.any(Char::isISOControl) ||
                (q.kind != QuestionKind.SINGLE_CHOICE && q.options.isNotEmpty()) ||
                (q.kind == QuestionKind.SINGLE_CHOICE && (q.options.isEmpty() ||
                    q.options.map { it.id }.distinct().size != q.options.size ||
                    q.options.map { it.title.trim().lowercase(java.util.Locale.ROOT) }.distinct().size != q.options.size ||
                    q.options.any { it.title.isBlank() || it.title.any(Char::isISOControl) }))
        }) add("questions")
    }

    fun questions(f: EventFields): List<RegistrationQuestion> = f.questions.map { q -> when (q.kind) {
        QuestionKind.TEXT -> RegistrationQuestion.Text(q.id, q.title, q.required)
        QuestionKind.CHECKBOX -> RegistrationQuestion.Checkbox(q.id, q.title, q.required)
        QuestionKind.SINGLE_CHOICE -> RegistrationQuestion.SingleChoice(q.id, q.title, q.required, q.options.toList())
    } }

    fun updateError(event: Event, fields: EventFields, now: Instant, activeCount: Int, hasRegistrations: Boolean): EventEditError? {
        if (event.lifecycle != EventLifecycle.PUBLISHED || event.endsAt?.let { now >= it } == true) return EventEditError.EventFinished
        val started = now >= event.startsAt
        val errors = publicationErrors(fields, now, allowStarted = started).toMutableSet()
        if (started && (fields.startsAt != event.startsAt || fields.endsAt != event.endsAt || fields.zoneId != event.zoneId)) errors += "datesLocked"
        if (fields.capacity != null && fields.capacity < activeCount) errors += "capacity"
        if (errors.isNotEmpty()) return EventEditError.Validation(errors)
        if (hasRegistrations && questions(fields) != event.questions) return EventEditError.QuestionsLocked
        return null
    }
}
