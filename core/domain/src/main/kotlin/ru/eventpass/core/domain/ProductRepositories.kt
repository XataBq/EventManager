package ru.eventpass.core.domain

import kotlinx.coroutines.flow.Flow
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
    fun observePublicEvent(eventId: EventId): Flow<PublicEventResult>
    suspend fun refreshPublicEvent(eventId: EventId)
}

interface RegistrationRepository {
    suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome
    suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand): AuthenticatedRegistrationOutcome
}

interface TicketRepository {
    fun observeMyTickets(): Flow<MyTicketsResult>
    fun observeOwnTicket(ticketId: TicketId): Flow<OwnTicketResult>
    suspend fun refreshMyTickets()
    suspend fun cancelOwnTicket(ticketId: TicketId): CancelTicketOutcome
}
