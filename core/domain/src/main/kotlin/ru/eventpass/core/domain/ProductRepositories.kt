package ru.eventpass.core.domain

import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.PublicEventResult
import ru.eventpass.core.domain.registration.GuestRegistrationCommand
import ru.eventpass.core.domain.registration.GuestRegistrationOutcome
import ru.eventpass.core.domain.registration.MyTicketsResult
import ru.eventpass.core.domain.event.TicketId
import ru.eventpass.core.domain.registration.AuthenticatedRegistrationCommand
import ru.eventpass.core.domain.registration.AuthenticatedRegistrationOutcome
import ru.eventpass.core.domain.registration.OwnTicketResult
import ru.eventpass.core.domain.registration.CancelTicketOutcome

interface EventRepository {
    fun observePublicEvent(reference: PublicEventReference): Flow<PublicEventResult>
    suspend fun refreshPublicEvent(reference: PublicEventReference)
    fun observePublicEvent(eventId: EventId): Flow<PublicEventResult> = observePublicEvent(PublicEventReference.Internal(eventId))
    suspend fun refreshPublicEvent(eventId: EventId) = refreshPublicEvent(PublicEventReference.Internal(eventId))
    fun observeMyEvents(): Flow<MyEventsResult>
    fun observeMyEvents(tab: MyEventsTab): Flow<MyEventsResult> = observeMyEvents()
    suspend fun refreshMyEvents(tab: MyEventsTab) = refreshMyEvents()
    suspend fun refreshEditor(target: EventEditorTarget) = refreshMyEvents()
    fun beginDraft(id: DraftId) {}
    fun observeEditor(target: EventEditorTarget): Flow<EventEditorResult>
    suspend fun refreshMyEvents()
    suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields): EventEditResult<EventDraft>
    suspend fun publishDraft(id: DraftId, expectedRevision: Long?): EventEditResult<EventId>
    suspend fun updateEvent(id: EventId, expectedRevision: Long?, expectedLocalSchemaRevision: Int?, fields: EventFields): EventEditResult<Event>
    suspend fun finishEvent(id: EventId, expectedRevision: Long?): EventEditResult<Event>
}

interface RegistrationRepository {
    suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome
    suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand): AuthenticatedRegistrationOutcome
}

interface TicketRepository {
    fun observeMyTickets(scope: ru.eventpass.core.domain.registration.TicketScope): Flow<MyTicketsResult> = observeMyTickets()
    suspend fun refreshMyTickets(scope: ru.eventpass.core.domain.registration.TicketScope) = refreshMyTickets()
    fun observeMyTickets(): Flow<MyTicketsResult>
    fun observeOwnTicket(ticketId: TicketId): Flow<OwnTicketResult>
    suspend fun refreshMyTickets()
    suspend fun cancelOwnTicket(ticketId: TicketId): CancelTicketOutcome
}
