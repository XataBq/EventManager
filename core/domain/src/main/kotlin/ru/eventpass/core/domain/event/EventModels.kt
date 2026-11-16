package ru.eventpass.core.domain.event

import java.time.Instant
import ru.eventpass.core.domain.registration.RegistrationQuestion
import ru.eventpass.core.domain.registration.RegistrationPolicy

@JvmInline value class EventId(val value: String)
@JvmInline value class EventSlug(val value: String) {
    init { require(value.isNotBlank() && value.codePointCount(0, value.length) <= 300 &&
        value !in setOf(".", "..") && value.none { it.isISOControl() || it in "/?#\\" }) }
}
sealed interface PublicEventReference {
    data class Slug(val slug: EventSlug) : PublicEventReference
    /** Explicit local/demo compatibility route; never implicitly sent as a remote slug. */
    data class Internal(val id: EventId) : PublicEventReference
}
@JvmInline value class UserId(val value: String)
@JvmInline value class TicketId(val value: String)

data class Account(val id: UserId, val email: String, val emailVerified: Boolean)

enum class EventLifecycle { DRAFT, PUBLISHED, LIVE, FINISHED, CANCELLED }

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
    val localSchemaRevision: Int?,
    val questions: List<RegistrationQuestion>,
    val description: String = "",
    val sections: List<EventSection> = emptyList(),
    val revision: Long? = null,
    val cover: EventCover? = null,
    val slug: EventSlug? = null,
    val finishedAt: Instant? = null,
    val priceType: PriceType = PriceType.FREE,
    val coverUrl: String? = null,
    val remoteCoverAssetId: String? = null,
) {
    val effectiveEndsAt: Instant get() = endsAt ?: startsAt.plusSeconds(4 * 3600L)
    fun statusAt(now: Instant): EventLifecycle = when {
        lifecycle == EventLifecycle.DRAFT -> EventLifecycle.DRAFT
        lifecycle == EventLifecycle.CANCELLED -> EventLifecycle.CANCELLED
        lifecycle == EventLifecycle.FINISHED || finishedAt != null || now >= effectiveEndsAt -> EventLifecycle.FINISHED
        now >= startsAt -> EventLifecycle.LIVE
        else -> EventLifecycle.PUBLISHED
    }
    init {
        require(lifecycle == EventLifecycle.DRAFT || (capacity != null && capacity > 0))
        require(endsAt == null || endsAt > startsAt)
        require(localSchemaRevision == null || localSchemaRevision > 0)
        require(revision == null || revision > 0)
        require(questions.map { it.id }.distinct().size == questions.size)
    }
}

enum class PriceType { FREE, PAID }

data class EventSection(val title: String, val time: String, val id: String = java.util.UUID.randomUUID().toString())

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
        statusAt(now) == EventLifecycle.FINISHED ->
            RegistrationClosureReason.FINISHED
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
        val verified = account
        val owner = verified?.id == event.ownerId
        val scannable = event.lifecycle !in setOf(EventLifecycle.DRAFT, EventLifecycle.FINISHED, EventLifecycle.CANCELLED) && event.finishedAt == null &&
            (availability as? RegistrationAvailability.Closed)?.reason !in setOf(RegistrationClosureReason.FINISHED, RegistrationClosureReason.CANCELLED, RegistrationClosureReason.NOT_PUBLISHED)
        val scanner = verified != null && grants.any {
            it.active && it.eventId == event.id && it.userId == verified.id
        }
        return EventAccess(
            canRegister = availability is RegistrationAvailability.Open && !hasOwnTicket,
            canViewOwnTicket = verified != null && hasOwnTicket,
            canManage = owner,
            canScan = scannable && (owner || scanner),
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
        val registeredCount: Int = 0,
        val checkedInCount: Int = 0,
        val ownTicketId: TicketId? = null,
    ) : PublicEventResult
    data object NotFound : PublicEventResult
    data class Failed(val error: ProductReadError) : PublicEventResult
}

data class RegistrationContact(val email: String, val name: String, val phone: String? = null)
enum class ProductReadError { FEATURE_UNAVAILABLE, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, PROTOCOL, FORBIDDEN, SERVER_UNAVAILABLE}
