package ru.eventpass.core.domain.event

import java.time.Instant
import java.time.ZoneId
import ru.eventpass.core.domain.registration.*

@JvmInline value class DraftId(val value: String)
enum class QuestionKind { TEXT, TEXTAREA, EMAIL, TEL, NUMBER, DATE, CHECKBOX, SINGLE_CHOICE, SELECT, MULTIPLE, UNSUPPORTED }

/** Incomplete editor input, including a choice with no options yet. IDs survive every save. */
data class DraftQuestion(
    val id: String,
    val title: String = "",
    val required: Boolean = false,
    val kind: QuestionKind = QuestionKind.TEXT,
    val options: List<RegistrationQuestion.Option> = emptyList(),
    val placeholder: String? = null,
    val help: String? = null,
    val rawType: String? = null,
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
    val priceType: PriceType = PriceType.FREE,
    val remoteCoverAssetId: String? = null,
    val remoteCoverUrl: String? = null,
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
    data object Protocol : EventEditError
    data object ServerUnavailable : EventEditError
    data object FeatureUnavailable : EventEditError
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

fun RegistrationQuestion.toDraftQuestion(): DraftQuestion = when (this) {
    is RegistrationQuestion.Text -> DraftQuestion(id, title, required, QuestionKind.valueOf(format.name), placeholder = placeholder, help = help)
    is RegistrationQuestion.Checkbox -> DraftQuestion(id, title, required, QuestionKind.CHECKBOX, placeholder = placeholder, help = help)
    is RegistrationQuestion.SingleChoice -> DraftQuestion(id, title, required, if (format == ChoiceFormat.SELECT) QuestionKind.SELECT else QuestionKind.SINGLE_CHOICE, options, placeholder, help)
    is RegistrationQuestion.MultipleChoice -> DraftQuestion(id, title, required, QuestionKind.MULTIPLE, options, placeholder = placeholder, help = help)
    is RegistrationQuestion.Unsupported -> DraftQuestion(id, title, required, QuestionKind.UNSUPPORTED, placeholder = placeholder, help = help, rawType = rawType)
}
fun Event.toFields() = EventFields(title, startsAt, endsAt, zoneId, venue, capacity, questions.map { it.toDraftQuestion() }, description, sections, cover, priceType, remoteCoverAssetId, coverUrl)

/** Local editor validation follows published API constraints; no write-version or frozen-form claim. */
object EventEditingPolicy {
    private fun textLength(value: String) = value.codePointCount(0, value.length)
    fun validId(value: String) = value.isNotBlank() && textLength(value) <= 300 && value.none(Char::isISOControl)
    fun storageErrors(f: EventFields): Set<String> = buildSet {
        if (textLength(f.title) > 300) add("title")
        if (textLength(f.venue) > 300) add("venue")
        if (f.zoneId.length > 100) add("zoneId")
        if (textLength(f.description) > 20_000) add("description")
        if (f.sections.size > 50 || f.sections.any { !validId(it.id) || textLength(it.title) > 300 || textLength(it.time) > 300 } || f.sections.map { it.id }.distinct().size != f.sections.size) add("sections")
        if (f.questions.size > 50 || f.questions.any { q ->
            !validId(q.id) || textLength(q.title) > 300 || q.options.size > 50 || q.options.any { textLength(it.id) > 300 || textLength(it.title) > 300 || (it.hint?.let(::textLength) ?: 0) > 300 } ||
                (q.placeholder?.let(::textLength) ?: 0) > 300 || (q.help?.let(::textLength) ?: 0) > 300
        } || f.questions.map { it.id }.distinct().size != f.questions.size) add("questions")
    }

    fun publicationErrors(f: EventFields, now: Instant, allowStarted: Boolean = false): Set<String> = buildSet {
        addAll(storageErrors(f))
        if (f.title.isBlank()) add("title")
        if (f.venue.isBlank()) add("venue")
        if (f.description.isBlank()) add("description")
        if (f.startsAt == null) add("startsAt")
        if (f.endsAt != null && (f.startsAt == null || f.endsAt <= f.startsAt)) add("endsAt")
        if (runCatching { ZoneId.of(f.zoneId) }.isFailure) add("zoneId")
        if (f.capacity == null || f.capacity !in 1..1_000_000) add("capacity")
        if (f.sections.any { it.title.isBlank() }) add("sections")
        if (f.questions.any { q ->
            q.title.isBlank() || q.kind == QuestionKind.UNSUPPORTED ||
                (q.kind in setOf(QuestionKind.SINGLE_CHOICE, QuestionKind.SELECT, QuestionKind.MULTIPLE) &&
                    (q.options.size < 2 || q.options.map { it.id }.distinct().size != q.options.size || q.options.any { it.id.isBlank() || it.title.isBlank() }))
        }) add("questions")
    }

    fun questions(f: EventFields): List<RegistrationQuestion> = f.questions.map { q -> when (q.kind) {
        QuestionKind.CHECKBOX -> RegistrationQuestion.Checkbox(q.id, q.title, q.required, q.help, q.placeholder)
        QuestionKind.SINGLE_CHOICE, QuestionKind.SELECT -> RegistrationQuestion.SingleChoice(q.id, q.title, q.required, q.options.toList(), if (q.kind == QuestionKind.SELECT) ChoiceFormat.SELECT else ChoiceFormat.CHOICE, q.placeholder, q.help)
        QuestionKind.MULTIPLE -> RegistrationQuestion.MultipleChoice(q.id, q.title, q.required, q.options.toList(), q.help, q.placeholder)
        QuestionKind.UNSUPPORTED -> RegistrationQuestion.Unsupported(q.id, q.title, q.required, q.rawType.orEmpty(), q.help, q.placeholder)
        else -> RegistrationQuestion.Text(q.id, q.title, q.required, TextFormat.valueOf(q.kind.name), q.placeholder, q.help)
    } }

    fun updateError(event: Event, fields: EventFields, now: Instant, activeCount: Int, hasRegistrations: Boolean): EventEditError? {
        if (event.statusAt(now) in setOf(EventLifecycle.FINISHED, EventLifecycle.CANCELLED)) return EventEditError.EventFinished
        val errors = publicationErrors(fields, now, allowStarted = true).toMutableSet()
        if (fields.capacity != null && fields.capacity < activeCount) errors += "capacity"
        if (errors.isNotEmpty()) return EventEditError.Validation(errors)
        return null
    }
}
