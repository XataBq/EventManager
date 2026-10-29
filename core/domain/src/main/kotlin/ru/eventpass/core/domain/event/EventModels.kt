package ru.eventpass.core.domain.event

import java.time.Instant
import ru.eventpass.core.domain.registration.RegistrationQuestion
import ru.eventpass.core.domain.registration.RegistrationPolicy

@JvmInline value class EventId(val value: String)
@JvmInline value class UserId(val value: String)
@JvmInline value class TicketId(val value: String)

data class Account(val id: UserId, val email: String, val emailVerified: Boolean)

enum class EventLifecycle { DRAFT, PUBLISHED, FINISHED, CANCELLED }

data class Event(
    val id: EventId,
    val ownerId: UserId,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val zoneId: String,
    val venue: String,
    val capacity: Int?,
    val lifecycle: EventLifecycle,
    val registrationOpen: Boolean,
    val formVersion: Int,
    val questions: List<RegistrationQuestion>,
    val description: String = "",
    val sections: List<EventSection> = emptyList(),
    val revision: Long = 1,
    val cover: EventCover? = null,
) {
    init {
        require(capacity == null || capacity >= 0)
        require(endsAt == null || endsAt > startsAt)
        require(formVersion > 0)
        require(revision > 0)
        require(questions.map { it.id }.distinct().size == questions.size)
    }
}

data class EventSection(val title: String, val time: String)

sealed interface AvailablePlaces {
    data object Unlimited : AvailablePlaces
    data class Limited(val remaining: Int) : AvailablePlaces {
        init { require(remaining >= 0) }
    }
}

enum class RegistrationClosureReason { NOT_PUBLISHED, CANCELLED, FINISHED, STARTED, CLOSED, FULL }

sealed interface RegistrationAvailability {
    data class Open(val places: AvailablePlaces) : RegistrationAvailability
    data class Closed(val reason: RegistrationClosureReason) : RegistrationAvailability
}

/** Server-like outcome for local demo data, not a client-side allocation algorithm. */
fun Event.registrationAvailability(now: Instant, registeredCount: Int): RegistrationAvailability {
    require(registeredCount >= 0)
    val reason = when {
        lifecycle == EventLifecycle.DRAFT -> RegistrationClosureReason.NOT_PUBLISHED
        lifecycle == EventLifecycle.CANCELLED -> RegistrationClosureReason.CANCELLED
        lifecycle == EventLifecycle.FINISHED || endsAt?.let { now >= it } == true ->
            RegistrationClosureReason.FINISHED
        now >= startsAt -> RegistrationClosureReason.STARTED
        !registrationOpen -> RegistrationClosureReason.CLOSED
        capacity != null && registeredCount >= capacity -> RegistrationClosureReason.FULL
        else -> null
    }
    return if (reason != null) RegistrationAvailability.Closed(reason)
    else RegistrationAvailability.Open(
        capacity?.let { AvailablePlaces.Limited(it - registeredCount) } ?: AvailablePlaces.Unlimited,
    )
}

data class ScanGrant(val eventId: EventId, val userId: UserId, val active: Boolean = true)

data class EventAccess(
    val canRegister: Boolean,
    val canViewOwnTicket: Boolean,
    val canManage: Boolean,
    val canScan: Boolean,
    val canViewAttendees: Boolean,
    val canExport: Boolean,
    val canManageVolunteers: Boolean,
)

object EventAccessPolicy {
    fun resolve(
        event: Event,
        account: Account?,
        hasOwnTicket: Boolean,
        grants: List<ScanGrant>,
        availability: RegistrationAvailability,
    ): EventAccess {
        val verified = account?.takeIf { it.emailVerified }
        val owner = verified?.id == event.ownerId
        val scanner = verified != null && grants.any {
            it.active && it.eventId == event.id && it.userId == verified.id
        }
        return EventAccess(
            canRegister = availability is RegistrationAvailability.Open && !hasOwnTicket,
            canViewOwnTicket = verified != null && hasOwnTicket,
            canManage = owner,
            canScan = owner || scanner,
            canViewAttendees = owner,
            canExport = owner,
            canManageVolunteers = owner,
        )
    }
}

sealed interface PublicEventResult {
    data class Found(
        val event: Event,
        val availability: RegistrationAvailability,
        val access: EventAccess,
        val policy: RegistrationPolicy = RegistrationPolicy(),
        val contact: RegistrationContact? = null,
    ) : PublicEventResult
    data object NotFound : PublicEventResult
    data class Failed(val error: ProductReadError) : PublicEventResult
}

data class RegistrationContact(val email: String, val name: String)
enum class ProductReadError { NETWORK, TIMEOUT, STORAGE_UNAVAILABLE }
