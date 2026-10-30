package ru.eventpass.core.domain.registration

import java.time.Instant
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.RegistrationClosureReason
import ru.eventpass.core.domain.event.TicketId
import ru.eventpass.core.domain.event.UserId
import ru.eventpass.core.domain.event.Event
import ru.eventpass.core.domain.event.EventLifecycle
import ru.eventpass.core.domain.event.ProductReadError

enum class FieldRequirement { REQUIRED, OPTIONAL, HIDDEN }
data class RegistrationPolicy(val phone: FieldRequirement = FieldRequirement.OPTIONAL)

sealed interface RegistrationQuestion {
    val id: String
    val title: String
    val required: Boolean

    data class Text(override val id: String, override val title: String, override val required: Boolean) :
        RegistrationQuestion
    data class Checkbox(override val id: String, override val title: String, override val required: Boolean) :
        RegistrationQuestion
    data class SingleChoice(
        override val id: String,
        override val title: String,
        override val required: Boolean,
        val options: List<Option>,
    ) : RegistrationQuestion {
        init {
            require(options.isNotEmpty())
            require(options.map { it.id }.distinct().size == options.size)
        }
    }
    data class Option(val id: String, val title: String)
}

sealed interface Answer {
    data class Text(val value: String) : Answer
    data class Checkbox(val checked: Boolean) : Answer
    data class SingleChoice(val optionId: String) : Answer
}

data class GuestRegistrationCommand(
    val eventId: EventId,
    val formVersion: Int,
    val name: String,
    val email: String,
    val phone: String? = null,
    val answers: Map<String, Answer> = emptyMap(),
    val consentAccepted: Boolean,
)

sealed interface RegistrationField {
    data object Name : RegistrationField
    data object Email : RegistrationField
    data object Phone : RegistrationField
    data object Consent : RegistrationField
    data class Question(val id: String) : RegistrationField
}
enum class FieldIssue { REQUIRED, INVALID_NAME, INVALID_EMAIL, INVALID_PHONE, INVALID_ANSWER, UNKNOWN_QUESTION }
data class FieldError(val field: RegistrationField, val issue: FieldIssue)

sealed interface RegistrationError {
    data class Validation(val fields: List<FieldError>) : RegistrationError
    data class Unavailable(val reason: RegistrationClosureReason) : RegistrationError
    data object NotFound : RegistrationError
    data object StaleForm : RegistrationError
    data object Network : RegistrationError
    data object Timeout : RegistrationError
    data object StorageUnavailable : RegistrationError
    data object AuthenticationRequired : RegistrationError
    data object SessionChanged : RegistrationError
}

/** Identity is resolved from the current verified session by the repository. */
data class AuthenticatedRegistrationCommand(
    val eventId: EventId, val formVersion: Int, val name: String,
    val phone: String? = null, val answers: Map<String, Answer> = emptyMap(), val consentAccepted: Boolean,
)
sealed interface AuthenticatedRegistrationOutcome {
    data class Registered(val ticketId: TicketId, val existing: Boolean) : AuthenticatedRegistrationOutcome
    data class Failed(val error: RegistrationError) : AuthenticatedRegistrationOutcome
}

/** Compact payload; the Android flow owns its saved-state encoding and lifetime (T05). */
data class GuestTicketSnapshot(
    val ticketId: TicketId,
    val eventId: EventId,
    val eventTitle: String,
    val startsAt: Instant,
    val zoneId: String,
    val venue: String,
    val attendeeName: String,
    val displayNumber: String,
    val qrPayload: String,
    val manualCode: ru.eventpass.core.domain.checkin.ManualCode = ru.eventpass.core.domain.checkin.ManualCodePolicy.fromQr(qrPayload),
) {
    override fun toString() = "GuestTicketSnapshot(ticketId=$ticketId, eventId=$eventId, credentials=[redacted])"
}

sealed interface GuestRegistrationOutcome {
    data class Issued(val ticket: GuestTicketSnapshot, val email: String) : GuestRegistrationOutcome
    data class RequiresLogin(val email: String) : GuestRegistrationOutcome
    data class ExistingRegistration(val email: String) : GuestRegistrationOutcome
    data class Failed(val error: RegistrationError) : GuestRegistrationOutcome
}

enum class TicketStatus { ACTIVE, USED, CANCELLED }

data class Registration(
    val id: String,
    val eventId: EventId,
    val userId: UserId?,
    val attendeeEmail: String,
    val attendeeName: String,
    val phone: String?,
    val formVersion: Int,
    val answers: Map<String, Answer>,
    val consentAcceptedAt: Instant,
)

data class Ticket(val snapshot: GuestTicketSnapshot, val registrationId: String, val status: TicketStatus)

sealed interface MyTicketsResult {
    data object AuthenticationRequired : MyTicketsResult
    data object EmailVerificationRequired : MyTicketsResult
    data class Ready(val tickets: List<Ticket>, val events: Map<EventId, Event> = emptyMap(), val now: Instant = Instant.EPOCH) : MyTicketsResult
    data class Failed(val error: ProductReadError) : MyTicketsResult
}

sealed interface OwnTicketResult {
    data class Found(val ticket: Ticket, val event: Event, val now: Instant) : OwnTicketResult
    data object AuthenticationRequired : OwnTicketResult
    data object NotFound : OwnTicketResult
    data class Failed(val error: ProductReadError) : OwnTicketResult
}
enum class TicketError { AUTHENTICATION_REQUIRED, NOT_FOUND, USED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, SESSION_CHANGED }
sealed interface CancelTicketOutcome {
    data class Cancelled(val ticket: Ticket) : CancelTicketOutcome
    data class Failed(val error: TicketError) : CancelTicketOutcome
}

fun Ticket.isCurrent(event: Event, now: Instant): Boolean = status == TicketStatus.ACTIVE &&
    event.lifecycle !in setOf(EventLifecycle.FINISHED, EventLifecycle.CANCELLED) &&
    (event.endsAt == null || now < event.endsAt)

fun Ticket.canDisplayQr(event: Event, now: Instant): Boolean = isCurrent(event, now)
