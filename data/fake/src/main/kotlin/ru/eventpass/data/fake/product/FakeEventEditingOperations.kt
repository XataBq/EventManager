package ru.eventpass.data.fake.product

import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.TicketStatus

/** Organizer commands share the exact auth/ticket store, Mutex and atomic storage path. */
internal class FakeEventEditingOperations(
    private val state: MutableStateFlow<FakeProductState>, private val lock: Mutex,
    private val clock: Clock, private val authClock: Clock,
    private val storage: FakeAuthStorage, private val auth: FakeAuthRepository,
) {
    private fun initialized(): Flow<FakeProductState> = flow { auth.initialize(); emitAll(state) }
    private fun readError(s: FakeProductState, account: Account?): EventEditError? = when {
        !s.auth.healthy -> EventEditError.StorageUnavailable
        account?.emailVerified != true -> EventEditError.AuthenticationRequired
        s.managementReadError != null -> s.managementReadError.toEditError()
        else -> null
    }
    fun observeMyEvents(): Flow<MyEventsResult> = initialized().map { s ->
        val account = s.activeAccount(authClock.instant())
        readError(s, account)?.let { return@map MyEventsResult.Failed(it) }
        val actor = requireNotNull(account).id
        MyEventsResult.Ready(s.events.values.filter { it.ownerId == actor },
            s.events.values.filter { e -> e.lifecycle != EventLifecycle.DRAFT && s.grants.any { it.active && it.userId == actor && it.eventId == e.id } },
            s.drafts.values.filter { it.ownerId == actor && it.publishedEventId == null }, clock.instant())
    }.distinctUntilChanged()

    fun observeEditor(target: EventEditorTarget): Flow<EventEditorResult> = initialized().map { s ->
        val account = s.activeAccount(authClock.instant())
        readError(s, account)?.let { return@map EventEditorResult.Failed(it) }
        val actor = requireNotNull(account).id
        when (target) {
            is EventEditorTarget.Draft -> {
                val draft = s.drafts[target.id]
                when {
                    draft == null -> EventEditorResult.Failed(EventEditError.NotFound)
                    draft.ownerId != actor -> EventEditorResult.Failed(EventEditError.Forbidden)
                    draft.publishedEventId != null -> published(s, requireNotNull(draft.publishedEventId), actor)
                    else -> EventEditorResult.Draft(draft)
                }
            }
            is EventEditorTarget.Published -> published(s, target.id, actor)
        }
    }.distinctUntilChanged()

    private fun published(s: FakeProductState, id: EventId, actor: UserId): EventEditorResult {
        val event = s.events[id] ?: return EventEditorResult.Failed(EventEditError.NotFound)
        return if (event.ownerId != actor) EventEditorResult.Failed(EventEditError.Forbidden)
        else EventEditorResult.Published(event, s.registrations.any { it.eventId == id }, clock.instant())
    }

    suspend fun refreshMyEvents() {
        auth.initialize()
        withContext(Dispatchers.IO) { lock.withLock {
            val s = state.value
            state.value = s.copy(managementReadError = s.nextReadError, nextReadError = null, revision = s.revision + 1)
        } }
    }

    suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields): EventEditResult<EventDraft> {
        val input = fields.detached()
        return mutate { s, actor ->
            if (!EventEditingPolicy.validId(id.value)) return@mutate failure(s, EventEditError.Validation(setOf("draftId")))
            val errors = EventEditingPolicy.storageErrors(input)
            if (errors.isNotEmpty()) return@mutate failure(s, EventEditError.Validation(errors))
            val old = s.drafts[id]
            if (old != null && old.ownerId != actor) return@mutate failure(s, EventEditError.Forbidden)
            if ((old == null && expectedRevision != null) || (old != null && (old.revision != expectedRevision || old.publishedEventId != null)))
                return@mutate failure(s, EventEditError.Conflict)
            val draft = EventDraft(id, actor, (old?.revision ?: 0) + 1, input)
            s.copy(drafts = s.drafts + (id to draft)) to EventEditResult.Saved(draft)
        }
    }

    suspend fun publishDraft(id: DraftId, expectedRevision: Long): EventEditResult<EventId> = mutate { s, actor ->
        val draft = s.drafts[id] ?: return@mutate failure(s, EventEditError.NotFound)
        if (draft.ownerId != actor) return@mutate failure(s, EventEditError.Forbidden)
        if (draft.revision != expectedRevision) return@mutate failure(s, EventEditError.Conflict)
        draft.publishedEventId?.let { return@mutate s to EventEditResult.Saved(it) }
        val fields = draft.fields
        val errors = EventEditingPolicy.publicationErrors(fields, clock.instant())
        if (errors.isNotEmpty()) return@mutate failure(s, EventEditError.Validation(errors))
        val eventId = EventId("event-${UUID.randomUUID()}")
        val event = Event(eventId, actor, fields.title, fields.startsAt!!, fields.endsAt, fields.zoneId, fields.venue,
            fields.capacity, EventLifecycle.PUBLISHED, true, 1, EventEditingPolicy.questions(fields), fields.description, fields.sections, cover = fields.cover)
        s.copy(events = s.events + (eventId to event), drafts = s.drafts + (id to draft.copy(publishedEventId = eventId))) to EventEditResult.Saved(eventId)
    }

    suspend fun updateEvent(id: EventId, expectedRevision: Long, expectedFormVersion: Int, fields: EventFields): EventEditResult<Event> {
        val input = fields.detached()
        return changeEvent(id, expectedRevision) { s, event ->
            if (event.formVersion != expectedFormVersion) return@changeEvent EventEditResult.Failed(EventEditError.StaleForm)
            EventEditingPolicy.updateError(event, input, clock.instant(),
                s.tickets.count { it.snapshot.eventId == id && it.status != TicketStatus.CANCELLED },
                s.registrations.any { it.eventId == id })?.let { return@changeEvent EventEditResult.Failed(it) }
            val questions = EventEditingPolicy.questions(input)
            EventEditResult.Saved(event.copy(title = input.title, startsAt = input.startsAt!!, endsAt = input.endsAt,
                zoneId = input.zoneId, venue = input.venue, capacity = input.capacity, questions = questions,
                formVersion = event.formVersion + if (questions == event.questions) 0 else 1,
                description = input.description, sections = input.sections, cover = input.cover, revision = event.revision + 1))
        }
    }

    suspend fun closeRegistration(id: EventId, expectedRevision: Long): EventEditResult<Event> = changeEvent(id, expectedRevision) { _, event ->
        EventEditResult.Saved(if (!event.registrationOpen) event else event.copy(registrationOpen = false, revision = event.revision + 1))
    }
    suspend fun finishEvent(id: EventId, expectedRevision: Long): EventEditResult<Event> = changeEvent(id, expectedRevision) { _, event ->
        if (event.lifecycle == EventLifecycle.CANCELLED) EventEditResult.Failed(EventEditError.EventFinished)
        else EventEditResult.Saved(if (event.lifecycle == EventLifecycle.FINISHED) event else
            event.copy(lifecycle = EventLifecycle.FINISHED, registrationOpen = false, revision = event.revision + 1))
    }

    private suspend fun changeEvent(id: EventId, expectedRevision: Long,
        change: (FakeProductState, Event) -> EventEditResult<Event>): EventEditResult<Event> = mutate { s, actor ->
        val event = s.events[id] ?: return@mutate failure(s, EventEditError.NotFound)
        if (event.ownerId != actor) return@mutate failure(s, EventEditError.Forbidden)
        if (event.revision != expectedRevision) return@mutate failure(s, EventEditError.Conflict)
        when (val result = change(s, event)) {
            is EventEditResult.Failed -> s to result
            is EventEditResult.Saved -> {
                val updated = result.value
                // Ticket display follows current metadata. Credentials, ownership, answers/status never change.
                s.copy(events = s.events + (id to updated), tickets = s.tickets.map { t ->
                    if (t.snapshot.eventId != id) t else t.copy(snapshot = t.snapshot.copy(eventTitle = updated.title,
                        startsAt = updated.startsAt, zoneId = updated.zoneId, venue = updated.venue))
                }) to result
            }
        }
    }

    private fun failure(s: FakeProductState, error: EventEditError) = s to EventEditResult.Failed(error)
    private suspend fun <T> mutate(action: (FakeProductState, UserId) -> Pair<FakeProductState, EventEditResult<T>>): EventEditResult<T> {
        auth.initialize()
        val generation = state.value.auth.generation
        return withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive()
            val s = state.value
            if (!s.auth.healthy) return@withLock EventEditResult.Failed(EventEditError.StorageUnavailable)
            if (s.auth.generation != generation) return@withLock EventEditResult.Failed(EventEditError.SessionChanged)
            val actor = s.activeAccount(authClock.instant())?.takeIf { it.emailVerified }?.id
                ?: return@withLock EventEditResult.Failed(EventEditError.AuthenticationRequired)
            s.nextEventEditError?.let {
                state.value = s.copy(nextEventEditError = null)
                return@withLock EventEditResult.Failed(it)
            }
            val (next, result) = action(s, actor)
            if (next != s) {
                try { storage.write(ProductSnapshotCodec.encode(next)) }
                catch (_: IOException) { return@withLock EventEditResult.Failed(EventEditError.StorageUnavailable) }
                catch (_: IllegalArgumentException) { return@withLock EventEditResult.Failed(EventEditError.StorageUnavailable) }
                state.value = next
            }
            result
        } }
    }
}

private fun EventFields.detached() = copy(questions = questions.map { it.copy(options = it.options.toList()) }, sections = sections.toList())
private fun ProductReadError.toEditError(): EventEditError = when (this) {
    ProductReadError.NETWORK -> EventEditError.Network
    ProductReadError.TIMEOUT -> EventEditError.Timeout
    ProductReadError.STORAGE_UNAVAILABLE -> EventEditError.StorageUnavailable
}
