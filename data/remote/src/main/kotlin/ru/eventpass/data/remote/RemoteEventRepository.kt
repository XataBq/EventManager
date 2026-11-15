package ru.eventpass.data.remote

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.AuthError
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

/** Only public reads are implemented here in M02; remaining ports keep explicit unavailable outcomes. */
class RemoteEventRepository(
    private val api: AuthHttp,
    private val auth: RemoteAuthRepository,
    unavailable: EventRepository,
) : EventRepository by unavailable {
    private class Entry {
        val clients = AtomicInteger()
        val lock = Mutex()
        val state = MutableStateFlow<Stamped?>(null)
    }
    private class Stamped(val context: PublicReadContext, val value: PublicEventResult, val confirmed: Boolean)
    private val entries = linkedMapOf<PublicEventReference.Slug, Entry>()

    override fun observePublicEvent(eventId: EventId): Flow<PublicEventResult> =
        observePublicEvent(PublicEventReference.Internal(eventId))

    override suspend fun refreshPublicEvent(eventId: EventId) =
        refreshPublicEvent(PublicEventReference.Internal(eventId))

    private fun entry(reference: PublicEventReference.Slug, subscribe: Boolean = false): Entry = synchronized(entries) {
        val resource = entries[reference] ?: Entry().also { created ->
            if (entries.size >= 32) entries.entries.firstOrNull {
                it.value.clients.get() == 0 && !it.value.lock.isLocked
            }?.let { entries.remove(it.key) }
            entries[reference] = created
        }
        if (subscribe) resource.clients.incrementAndGet()
        resource
    }

    private fun release(reference: PublicEventReference.Slug, resource: Entry, subscriber: Boolean) = synchronized(entries) {
        if (subscriber) resource.clients.decrementAndGet()
        if (resource.clients.get() == 0) {
            // Do not retain an inactive viewer/profile/bearer across a later logout.
            resource.state.value = null
            if (entries[reference] === resource) entries.remove(reference)
        }
    }

    override fun observePublicEvent(reference: PublicEventReference): Flow<PublicEventResult> {
        if (reference !is PublicEventReference.Slug) return flowOf(PublicEventResult.Failed(ProductReadError.FEATURE_UNAVAILABLE))
        return channelFlow<Stamped> {
            val resource = entry(reference, subscribe = true)
            try {
                launch {
                    combine(resource.state, auth.observeSession()) { saved, _ -> saved }
                        .filterNotNull().collect { saved ->
                            send(saved)
                        }
                }
                launch {
                    auth.observeSession().collectLatest {
                        val context = auth.capturePublicRead()
                        val saved = resource.state.value
                        if (saved != null && !auth.isCurrentPublicRead(saved.context)) {
                            resource.state.value = Stamped(context, saved.value.publicOnly(), false)
                        }
                        resource.lock.withLock {
                            val current = resource.state.value
                            if (current == null || !current.confirmed || !auth.isCurrentPublicRead(current.context)) {
                                read(reference, resource)
                            }
                        }
                    }
                }
                awaitCancellation()
            } finally {
                release(reference, resource, subscriber = true)
            }
        }.map { saved ->
            // Check at the consumer edge too: the channel may contain an older actor's result.
            if (auth.isCurrentPublicRead(saved.context)) saved.value else saved.value.publicOnly()
        }.distinctUntilChanged()
    }

    override suspend fun refreshPublicEvent(reference: PublicEventReference) {
        if (reference !is PublicEventReference.Slug) return
        val resource = entry(reference)
        if (!resource.lock.tryLock()) return
        try { read(reference, resource) } finally {
            resource.lock.unlock()
            release(reference, resource, subscriber = false)
        }
    }

    private suspend fun read(reference: PublicEventReference.Slug, resource: Entry) {
        val context = auth.capturePublicRead()
        val result = try {
            // Encode one path segment once; internal IDs never become public slugs.
            val path = "https://path.invalid/".toHttpUrl().newBuilder()
                .addPathSegment("events").addPathSegment(reference.slug.value).build().encodedPath.removePrefix("/")
            val text = auth.requestPublic(path, context) ?: return
            mapPublicEvent(api.json.decodeFromString<PublicEventDto>(text), context)
        } catch (e: CancellationException) { throw e
        } catch (e: RemoteFailure) {
            if (e.httpStatus == 404) PublicEventResult.NotFound else PublicEventResult.Failed(when (e.error) {
                AuthError.Network -> ProductReadError.NETWORK
                AuthError.Timeout -> ProductReadError.TIMEOUT
                AuthError.Forbidden -> ProductReadError.FORBIDDEN
                AuthError.ServerUnavailable -> ProductReadError.SERVER_UNAVAILABLE
                AuthError.StorageUnavailable -> ProductReadError.STORAGE_UNAVAILABLE
                else -> ProductReadError.PROTOCOL
            })
        } catch (e: SerializationException) { PublicEventResult.Failed(ProductReadError.PROTOCOL)
        } catch (e: IllegalArgumentException) { PublicEventResult.Failed(ProductReadError.PROTOCOL)
        } catch (e: java.time.DateTimeException) { PublicEventResult.Failed(ProductReadError.PROTOCOL) }
        if (auth.isCurrentPublicRead(context)) resource.state.value = Stamped(context, result, true)
    }
}

private fun PublicEventResult.publicOnly(): PublicEventResult = if (this is PublicEventResult.Found) copy(
    access = EventAccess(availability is RegistrationAvailability.Open, false, false, false, false, false, false),
    contact = null, ownTicketId = null,
) else this

@Serializable internal data class PublicEventDto(val event: EventDto, val viewer: ViewerDto)
@Serializable internal data class ViewerDto(val ticketId: String?, val isOrganizer: Boolean, val isVolunteer: Boolean)
@Serializable internal data class EventDto(
    val id: String, val slug: String, val organizerId: String, val title: String, val status: String,
    val startsAt: String?, val endsAt: String?, val effectiveEndsAt: String?, val finishedAt: String?,
    val location: String, val capacity: Int?, val registeredCount: Int, val checkedInCount: Int,
    val description: String, val priceType: String, val questions: List<QuestionDto>,
    val sections: List<SectionDto>, val coverAssetId: String? = null, val coverUrl: String? = null,
)
@Serializable internal data class SectionDto(val id: String, val title: String, val time: String)
@Serializable internal data class OptionDto(val value: String, val label: String, val hint: String? = null)
@Serializable internal data class QuestionDto(
    val id: String, val label: String, val type: String, val required: Boolean,
    val placeholder: String? = null, val help: String? = null, val options: List<OptionDto>? = null,
)

internal fun mapPublicEvent(dto: PublicEventDto, context: PublicReadContext): PublicEventResult.Found {
    val e = dto.event
    require(e.id.isNotBlank() && e.organizerId.isNotBlank() && e.title.isNotBlank() && e.location.isNotBlank())
    require(e.title.codePointCount(0, e.title.length) <= 300 && e.location.codePointCount(0, e.location.length) <= 300)
    require(e.description.codePointCount(0, e.description.length) <= 20000)
    require(e.capacity != null && e.capacity > 0 && e.registeredCount >= 0 && e.checkedInCount in 0..e.registeredCount)
    val status = when (e.status) {
        "published" -> EventLifecycle.PUBLISHED
        "live" -> EventLifecycle.LIVE
        "finished" -> EventLifecycle.FINISHED
        else -> throw IllegalArgumentException("Unexpected public event status")
    }
    val starts = Instant.parse(requireNotNull(e.startsAt))
    val ends = e.endsAt?.let(Instant::parse)
    require(Instant.parse(requireNotNull(e.effectiveEndsAt)) == (ends ?: starts.plusSeconds(14400)))
    require(e.questions.size <= 50 && e.sections.size <= 50)
    require(e.sections.map { it.id }.distinct().size == e.sections.size)
    val event = Event(EventId(e.id), UserId(e.organizerId), e.title, starts, ends, "Europe/Moscow", e.location,
        e.capacity, status, true, null, e.questions.map(::mapQuestion), e.description,
        e.sections.map {
            require(it.id.isNotBlank() && it.title.isNotBlank())
            require(listOf(it.id, it.title, it.time).all { value -> value.codePointCount(0, value.length) <= 300 })
            EventSection(it.title, it.time, it.id)
        },
        revision = null, slug = EventSlug(e.slug), finishedAt = e.finishedAt?.let(Instant::parse),
        priceType = when (e.priceType) { "free" -> PriceType.FREE; "paid" -> PriceType.PAID; else -> throw IllegalArgumentException("Unknown price type") },
        coverUrl = e.coverUrl, remoteCoverAssetId = e.coverAssetId)
    val available = when {
        status == EventLifecycle.FINISHED || event.finishedAt != null -> RegistrationAvailability.Closed(RegistrationClosureReason.FINISHED)
        e.registeredCount >= e.capacity -> RegistrationAvailability.Closed(RegistrationClosureReason.FULL)
        else -> RegistrationAvailability.Open(AvailablePlaces.Limited(e.capacity - e.registeredCount))
    }
    val authenticated = context.profile != null && context.token != null
    val owner = authenticated && dto.viewer.isOrganizer
    val scanner = authenticated && dto.viewer.isVolunteer
    val own = dto.viewer.ticketId?.takeIf { authenticated && it.isNotBlank() }?.let(::TicketId)
    val active = status != EventLifecycle.FINISHED && event.finishedAt == null
    return PublicEventResult.Found(event, available,
        EventAccess(available is RegistrationAvailability.Open && own == null, own != null, owner,
            active && (owner || scanner), owner, owner, owner),
        contact = context.profile?.let { RegistrationContact(it.account.email, it.fields.name, it.fields.phone) },
        registeredCount = e.registeredCount, checkedInCount = e.checkedInCount, ownTicketId = own)
}

private fun mapQuestion(q: QuestionDto): RegistrationQuestion {
    require(q.id.isNotBlank() && q.label.isNotBlank())
    require(listOfNotNull(q.id, q.label, q.placeholder, q.help).all { it.codePointCount(0, it.length) <= 300 })
    val options = q.options.orEmpty().map {
        require(it.value.isNotBlank() && it.label.isNotBlank())
        require(listOfNotNull(it.value, it.label, it.hint).all { value -> value.codePointCount(0, value.length) <= 300 })
        RegistrationQuestion.Option(it.value, it.label, it.hint)
    }
    require(options.size <= 50 && options.map { it.id }.distinct().size == options.size)
    return when (q.type) {
        "text", "textarea", "email", "tel", "number", "date" -> RegistrationQuestion.Text(q.id, q.label, q.required, TextFormat.valueOf(q.type.uppercase(java.util.Locale.ROOT)), q.placeholder, q.help)
        "checkbox" -> RegistrationQuestion.Checkbox(q.id, q.label, q.required, q.help, q.placeholder)
        "choice", "select" -> {
            require(options.size >= 2)
            RegistrationQuestion.SingleChoice(q.id, q.label, q.required, options, ChoiceFormat.valueOf(q.type.uppercase(java.util.Locale.ROOT)), q.placeholder, q.help)
        }
        "multiple" -> { require(options.size >= 2); RegistrationQuestion.MultipleChoice(q.id, q.label, q.required, options, q.help, q.placeholder) }
        else -> RegistrationQuestion.Unsupported(q.id, q.label, q.required, q.type, q.help, q.placeholder)
    }
}
