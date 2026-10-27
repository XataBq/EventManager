package ru.eventpass.core.domain

import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.PublicEventResult
import ru.eventpass.core.domain.registration.GuestRegistrationCommand
import ru.eventpass.core.domain.registration.GuestRegistrationOutcome
import ru.eventpass.core.domain.registration.MyTicketsResult

interface EventRepository {
    fun observePublicEvent(eventId: EventId): Flow<PublicEventResult>
    suspend fun refreshPublicEvent(eventId: EventId)
}

interface RegistrationRepository {
    suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome
}

interface TicketRepository {
    fun observeMyTickets(): Flow<MyTicketsResult>
}
