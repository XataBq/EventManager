package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.TicketId
import ru.eventpass.core.domain.registration.GuestTicketSnapshot

@Serializable
private data class SavedReceipt(
    val ticketId: String, val eventId: String, val title: String, val startsAt: String,
    val zoneId: String, val venue: String, val name: String, val number: String, val payload: String, val email: String,
)
data class GuestReceipt(val ticket: GuestTicketSnapshot, val email: String) {
    override fun toString() = "GuestReceipt([redacted])"
}
/** Owned by exactly one EventKey entry. No disk adapter or lookup by arbitrary ID. */
class GuestReceiptState(private val saved: SavedStateHandle) {
    fun read(): GuestReceipt? = saved.get<String>("guestReceipt")?.let { value ->
        val s = Json.decodeFromString<SavedReceipt>(value)
        GuestReceipt(GuestTicketSnapshot(TicketId(s.ticketId), EventId(s.eventId), s.title,
            Instant.parse(s.startsAt), s.zoneId, s.venue, s.name, s.number, s.payload), s.email)
    }
    fun write(ticket: GuestTicketSnapshot, email: String) {
        saved["guestReceipt"] = Json.encodeToString(SavedReceipt(ticket.ticketId.value, ticket.eventId.value,
            ticket.eventTitle, ticket.startsAt.toString(), ticket.zoneId, ticket.venue, ticket.attendeeName,
            ticket.displayNumber, ticket.qrPayload, email))
    }
    fun clear() { saved.remove<String>("guestReceipt"); saved.remove<Boolean>("guestFullscreen") }
}
