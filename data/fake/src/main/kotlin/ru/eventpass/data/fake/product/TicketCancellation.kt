package ru.eventpass.data.fake.product

import ru.eventpass.core.domain.registration.*

/** Status transition only; self/owner authorization stays in their guarded operations. */
internal fun FakeProductState.cancelTicket(ticket: Ticket): Pair<FakeProductState, CancelTicketOutcome> {
    if (ticket.status == TicketStatus.USED) return this to CancelTicketOutcome.Failed(TicketError.USED)
    if (ticket.status == TicketStatus.CANCELLED) return this to CancelTicketOutcome.Cancelled(ticket)
    val cancelled = ticket.copy(status = TicketStatus.CANCELLED)
    return copy(tickets = tickets.map { if (it.snapshot.ticketId == ticket.snapshot.ticketId) cancelled else it }) to CancelTicketOutcome.Cancelled(cancelled)
}
