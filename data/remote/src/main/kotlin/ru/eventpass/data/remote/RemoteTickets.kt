@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.eventpass.data.remote

import java.time.Clock
import java.time.Instant
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.eventpass.core.domain.*
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.checkin.ManualCode
import ru.eventpass.core.domain.documents.*

internal fun apiPath(vararg segments: String): String = "https://path.invalid/".toHttpUrl().newBuilder().apply {
    segments.forEach { require(it.isNotBlank() && it !in setOf(".", "..")); addPathSegment(it) }
}.build().encodedPath.removePrefix("/")

@Serializable internal data class TicketEventDto(val id: String, val slug: String, val title: String,
    val status: String, val startsAt: String?, val endsAt: String?, val effectiveEndsAt: String?,
    val finishedAt: String?, val location: String, val capacity: Int?, val registeredCount: Int,
    val checkedInCount: Int, val category: String)
@Serializable internal data class TicketDto(val id: String, val code: String, val qrToken: String,
    val status: String, val holderName: String, val holderEmail: String, val category: String,
    val zone: String = "", val createdAt: String, val checkedInAt: String?, val event: TicketEventDto)
@Serializable internal data class RegistrationDto(val ticket: TicketDto, val accountCreated: Boolean, val accessToken: String? = null)
@Serializable internal data class MyTicketsDto(val items: List<TicketDto>, val counters: Map<String, Int>)

internal fun TicketDto.domain(access: String? = null): Ticket {
    require(id.isNotBlank() && qrToken.isNotBlank() && qrToken.length <= 4096)
    require(code.matches(Regex("EVT-[A-Za-z0-9]{6}")))
    val e = event
    require(e.id.isNotBlank() && e.title.isNotBlank() && e.location.isNotBlank())
    val status = when(status) { "active" -> TicketStatus.ACTIVE; "used" -> TicketStatus.USED; "cancelled" -> TicketStatus.CANCELLED; else -> errorProtocol() }
    val lifecycle = when(e.status) { "published" -> EventLifecycle.PUBLISHED; "live" -> EventLifecycle.LIVE; "finished" -> EventLifecycle.FINISHED; else -> errorProtocol() }
    val starts = Instant.parse(requireNotNull(e.startsAt))
    val effective = Instant.parse(requireNotNull(e.effectiveEndsAt))
    require(effective == (e.endsAt?.let(Instant::parse) ?: starts.plusSeconds(14400)))
    val snapshot = GuestTicketSnapshot(TicketId(id), EventId(e.id), e.title, starts, "Europe/Moscow",
        e.location, holderName, code, qrToken, ManualCode(code), access)
    // The wire contract has no registration ID; preserve that absence explicitly.
    require(e.registeredCount >= 0 && e.checkedInCount in 0..e.registeredCount)
    return Ticket(snapshot, null, status, TicketEventSummary(EventId(e.id), EventSlug(e.slug), lifecycle,
        effective, e.finishedAt?.let(Instant::parse), e.endsAt?.let(Instant::parse), e.capacity, e.registeredCount, e.checkedInCount, e.category), holderEmail, category, zone,
        Instant.parse(createdAt), checkedInAt?.let(Instant::parse))
}
private fun errorProtocol(): Nothing = throw IllegalArgumentException("Unexpected ticket value")

/** Transient operation fingerprints only. Request bodies/guest capabilities never enter a disk cache. */
class RemoteRegistrations(private val api: AuthHttp, private val auth: RemoteAuthRepository,
    private val clock: Clock = Clock.systemUTC()) : RegistrationRepository {
    private data class Attempt(val digest: List<Byte>, val at: Instant)
    private val attempts = linkedMapOf<String, Attempt>()
    private val gate = Mutex()
    private var retryAt: Instant? = null
    private var actor: Long? = null
    override suspend fun submitGuest(command: GuestRegistrationCommand): GuestRegistrationOutcome {
        val owner = auth.capturePublicRead()
        val result = submit(command, guest = true, expected = owner)
        if(!auth.isCurrentPublicRead(owner)) return GuestRegistrationOutcome.Failed(RegistrationError.SessionChanged)
        return when(result) {
            is RegistrationDto -> try {
                require(result.accountCreated && !result.accessToken.isNullOrBlank() && result.accessToken.length <= 128)
                val ticket = result.ticket.domain(result.accessToken)
                require(ticket.snapshot.eventId == command.eventId)
                if(!auth.isCurrentPublicRead(owner)) GuestRegistrationOutcome.Failed(RegistrationError.SessionChanged)
                else GuestRegistrationOutcome.Issued(ticket.snapshot, result.ticket.holderEmail, true)
            } catch(_: IllegalArgumentException) { GuestRegistrationOutcome.Failed(RegistrationError.Protocol) }
              catch(_: java.time.DateTimeException) { GuestRegistrationOutcome.Failed(RegistrationError.Protocol) }
            "EMAIL_TAKEN" -> GuestRegistrationOutcome.RequiresLogin(command.email)
            "ALREADY_REGISTERED" -> GuestRegistrationOutcome.ExistingRegistration(command.email)
            else -> GuestRegistrationOutcome.Failed(result as RegistrationError)
        }
    }
    override suspend fun submitAuthenticated(command: AuthenticatedRegistrationCommand): AuthenticatedRegistrationOutcome {
        val owner = auth.capturePublicRead()
        val result = submit(GuestRegistrationCommand(command.eventId, command.localSchemaRevision, command.name,
            "", command.phone, command.answers, command.consentAccepted, command.operationId), guest = false, expected = owner)
        return when(result) {
            is RegistrationDto -> try {
                require(!result.accountCreated && result.accessToken == null)
                val ticket = result.ticket.domain()
                require(ticket.snapshot.eventId == command.eventId)
                if(!auth.isCurrentPublicRead(owner)) AuthenticatedRegistrationOutcome.Failed(RegistrationError.SessionChanged)
                else AuthenticatedRegistrationOutcome.Registered(ticket.snapshot.ticketId, false)
            } catch(_: IllegalArgumentException) { AuthenticatedRegistrationOutcome.Failed(RegistrationError.Protocol) }
              catch(_: java.time.DateTimeException) { AuthenticatedRegistrationOutcome.Failed(RegistrationError.Protocol) }
            "ALREADY_REGISTERED" -> findExisting(command.eventId, owner)
            else -> AuthenticatedRegistrationOutcome.Failed(result as? RegistrationError ?: RegistrationError.Protocol)
        }
    }
    private suspend fun findExisting(eventId: EventId, owner: PublicReadContext): AuthenticatedRegistrationOutcome {
        if(!auth.isCurrentPublicRead(owner)) return AuthenticatedRegistrationOutcome.Failed(RegistrationError.SessionChanged)
        try {
            for(scope in TicketScope.entries) {
                val body = auth.requestScoped("me/tickets?scope="+scope.name.lowercase(),owner)
                    ?: return AuthenticatedRegistrationOutcome.Failed(RegistrationError.SessionChanged)
                val dto = api.json.decodeFromString<MyTicketsDto>(body)
                val matches = dto.items.filter { it.event.id == eventId.value && it.status != "cancelled" }
                if(matches.size > 1) return AuthenticatedRegistrationOutcome.Failed(RegistrationError.Protocol)
                matches.singleOrNull()?.domain()?.let { return AuthenticatedRegistrationOutcome.Registered(it.snapshot.ticketId, true) }
            }
            return AuthenticatedRegistrationOutcome.Failed(RegistrationError.RecoveryRequired)
        } catch(e: RemoteFailure) { return AuthenticatedRegistrationOutcome.Failed(when(e.error) {
            AuthError.Network -> RegistrationError.Network; AuthError.Timeout -> RegistrationError.Timeout
            AuthError.Unauthorized -> RegistrationError.AuthenticationRequired; AuthError.Forbidden -> RegistrationError.Forbidden
            AuthError.ServerUnavailable -> RegistrationError.ServerUnavailable; else -> RegistrationError.Protocol
        }) } catch(_: SerializationException) { return AuthenticatedRegistrationOutcome.Failed(RegistrationError.Protocol) }
          catch(_: IllegalArgumentException) { return AuthenticatedRegistrationOutcome.Failed(RegistrationError.Protocol) }
          catch(_: java.time.DateTimeException) { return AuthenticatedRegistrationOutcome.Failed(RegistrationError.Protocol) }
    }
    private suspend fun submit(c: GuestRegistrationCommand, guest: Boolean, expected: PublicReadContext): Any {
        if(!gate.tryLock()) return RegistrationError.PendingRetry
        try {
            val context = expected
            if(!auth.isCurrentPublicRead(context)) return RegistrationError.SessionChanged
            if(actor != context.epoch) { attempts.clear(); retryAt = null; actor = context.epoch }
            if(!guest && context.token == null) return RegistrationError.AuthenticationRequired
            if(guest && context.token != null) return RegistrationError.SessionChanged
            if(retryAt?.let { clock.instant() < it } == true) return RegistrationError.RateLimited(requireNotNull(retryAt))
            val body = buildJsonObject {
                put("consentAccepted", c.consentAccepted)
                put("answers", buildJsonObject { c.answers.forEach { (id, a) -> put(id, when(a) {
                    is Answer.Text -> JsonPrimitive(a.value); is Answer.Checkbox -> JsonPrimitive(a.checked)
                    is Answer.SingleChoice -> JsonPrimitive(a.optionId)
                    is Answer.MultipleChoice -> JsonArray(a.optionIds.map(::JsonPrimitive))
                }) } })
                if(guest) put("guest", buildJsonObject { put("fullName", c.name); put("email", c.email); put("phone", c.phone.orEmpty()) })
            }.toString()
            val digest = MessageDigest.getInstance("SHA-256").digest((c.eventId.value + "\u0000" + body).toByteArray()).toList()
            val previous = attempts[c.operationId]
            if(previous != null && (previous.digest != digest || clock.instant() >= previous.at.plusSeconds(86400))) return RegistrationError.RecoveryRequired
            if(previous == null) {
                if(attempts.size >= 32) return RegistrationError.RecoveryRequired
                attempts[c.operationId] = Attempt(digest, clock.instant())
            }
            return try {
                val text = auth.requestScoped(apiPath("events", c.eventId.value, "registrations"), context, body, c.operationId)
                    ?: return RegistrationError.SessionChanged
                api.json.decodeFromString<RegistrationDto>(text).also { attempts.remove(c.operationId) }
            } catch(e: RemoteFailure) {
                if(!auth.isCurrentPublicRead(context)) return RegistrationError.SessionChanged
                if(e.httpStatus in listOf(400,401,403,404,409,422)) attempts.remove(c.operationId)
                if(e.error is AuthError.Cooldown) { retryAt = e.error.retryAt; return RegistrationError.RateLimited(e.error.retryAt) }
                if(e.httpStatus == 422 && e.fields.isNotEmpty()) return RegistrationError.Validation(e.fields.keys.map { key ->
                    val field = when(key) {
                        "guest.fullName" -> RegistrationField.Name; "guest.email" -> RegistrationField.Email
                        "guest.phone" -> RegistrationField.Phone; "consentAccepted" -> RegistrationField.Consent
                        else -> RegistrationField.Question(key.removePrefix("answers."))
                    }
                    FieldError(field, when(field) { RegistrationField.Name -> FieldIssue.INVALID_NAME; RegistrationField.Email -> FieldIssue.INVALID_EMAIL; RegistrationField.Phone -> FieldIssue.INVALID_PHONE; RegistrationField.Consent -> FieldIssue.REQUIRED; else -> FieldIssue.INVALID_ANSWER })
                })
                when(e.machineCode) {
                    "EMAIL_TAKEN", "ALREADY_REGISTERED" -> e.machineCode!!
                    "EVENT_FULL" -> RegistrationError.Unavailable(RegistrationClosureReason.FULL)
                    "EVENT_FINISHED" -> RegistrationError.Unavailable(RegistrationClosureReason.FINISHED)
                    "IDEMPOTENCY_CONFLICT" -> RegistrationError.RecoveryRequired
                    else -> when(e.error) {
                        AuthError.Network -> RegistrationError.Network; AuthError.Timeout -> RegistrationError.Timeout
                        AuthError.Unauthorized -> RegistrationError.AuthenticationRequired
                        AuthError.Forbidden -> RegistrationError.Forbidden
                        AuthError.ServerUnavailable -> RegistrationError.ServerUnavailable
                        else -> RegistrationError.Protocol
                    }
                }
            } catch(_: SerializationException) { RegistrationError.Protocol }
              catch(_: IllegalArgumentException) { RegistrationError.Protocol }
        } finally { gate.unlock() }
    }
}

class RemoteTickets(private val api: AuthHttp, private val auth: RemoteAuthRepository,
    private val clock: Clock = Clock.systemUTC()) : TicketRepository {
    private val ownUpdates=java.util.concurrent.ConcurrentHashMap<TicketId,MutableStateFlow<Long>>()
    private val knownEvents=java.util.concurrent.ConcurrentHashMap<TicketScope,Set<EventId>>()
    internal fun invalidateOwnerCancellation(eventId:EventId,ticketId:TicketId) {
        ownUpdates[ticketId]?.update {it+1}
        knownEvents.filterValues {eventId in it}.keys.forEach {scope->revisions.getValue(scope).update {it+1}}
    }
    private val revisions = TicketScope.entries.associateWith { MutableStateFlow(0L) }
    private val completed = TicketScope.entries.associateWith { MutableStateFlow(0L) }
    private val readers = TicketScope.entries.associateWith { java.util.concurrent.atomic.AtomicInteger() }
    private val refreshLocks = TicketScope.entries.associateWith { Mutex() }
    override fun observeMyTickets() = observeMyTickets(TicketScope.UPCOMING)
    override suspend fun refreshMyTickets() = refreshMyTickets(TicketScope.UPCOMING)
    override suspend fun refreshMyTickets(scope: TicketScope) {
        val lock = refreshLocks.getValue(scope)
        if(!lock.tryLock()) return
        try {
            val revision = revisions.getValue(scope).value + 1
            revisions.getValue(scope).value = revision
            if(readers.getValue(scope).get() > 0) completed.getValue(scope).first { it >= revision }
        } finally { lock.unlock() }
    }
    override fun observeMyTickets(scope: TicketScope): Flow<MyTicketsResult> =
        combine(auth.observeSession(), revisions.getValue(scope)) { session, revision -> session to revision }.transformLatest { (_, revision) ->
          try {
            val ctx = auth.capturePublicRead()
            if(ctx.token == null) { emit(MyTicketsResult.AuthenticationRequired); return@transformLatest }
            val result = try {
                val text = auth.requestScoped("me/tickets?scope=" + scope.name.lowercase(), ctx) ?: return@transformLatest
                val dto = api.json.decodeFromString<MyTicketsDto>(text)
                require(dto.items.map { it.id }.distinct().size == dto.items.size)
                val counters = TicketScope.entries.associateWith { requireNotNull(dto.counters[it.name.lowercase()]).also { n -> require(n >= 0) } }
                val tickets=dto.items.map {it.domain()}
                if(auth.isCurrentPublicRead(ctx)) knownEvents[scope]=tickets.map {it.snapshot.eventId}.toSet()
                MyTicketsResult.Ready(tickets, now = clock.instant(), scope = scope, counters = counters)
            } catch(e: RemoteFailure) { MyTicketsResult.Failed(e.readError()) }
              catch(_: SerializationException) { MyTicketsResult.Failed(ProductReadError.PROTOCOL) }
              catch(_: IllegalArgumentException) { MyTicketsResult.Failed(ProductReadError.PROTOCOL) }
              catch(_: java.time.DateTimeException) { MyTicketsResult.Failed(ProductReadError.PROTOCOL) }
            if(auth.isCurrentPublicRead(ctx)) emit(result) else emit(MyTicketsResult.AuthenticationRequired)
          } finally { completed.getValue(scope).update { maxOf(it, revision) } }
        }.onStart { readers.getValue(scope).incrementAndGet() }
         .onCompletion { readers.getValue(scope).decrementAndGet(); completed.getValue(scope).value = revisions.getValue(scope).value }
         .buffer(0)
    override fun observeOwnTicket(ticketId: TicketId): Flow<OwnTicketResult> = combine(auth.observeSession(),ownUpdates.getOrPut(ticketId){MutableStateFlow(0L)}) {session,version->session to version}.transformLatest {
        val ctx = auth.capturePublicRead()
        if(ctx.token == null) { emit(OwnTicketResult.AuthenticationRequired); return@transformLatest }
        val result = try {
            val text = auth.requestScoped(apiPath("tickets", ticketId.value), ctx) ?: return@transformLatest
            val ticket = api.json.decodeFromString<TicketDto>(text).domain()
            require(ticket.snapshot.ticketId == ticketId)
            OwnTicketResult.Found(ticket, null, clock.instant())
        } catch(e: RemoteFailure) { if(e.httpStatus == 404) OwnTicketResult.NotFound else OwnTicketResult.Failed(e.readError()) }
          catch(_: SerializationException) { OwnTicketResult.Failed(ProductReadError.PROTOCOL) }
          catch(_: IllegalArgumentException) { OwnTicketResult.Failed(ProductReadError.PROTOCOL) }
          catch(_: java.time.DateTimeException) { OwnTicketResult.Failed(ProductReadError.PROTOCOL) }
        if(auth.isCurrentPublicRead(ctx)) emit(result) else emit(OwnTicketResult.AuthenticationRequired)
    }.buffer(0)
    override suspend fun cancelOwnTicket(ticketId: TicketId): CancelTicketOutcome {
        val ctx = auth.capturePublicRead()
        if(ctx.token == null) return CancelTicketOutcome.Failed(TicketError.AUTHENTICATION_REQUIRED)
        return try {
            val text = auth.requestScoped(apiPath("tickets", ticketId.value, "cancel"), ctx, "{}")
                ?: return CancelTicketOutcome.Failed(TicketError.SESSION_CHANGED)
            val ticket = api.json.decodeFromString<TicketDto>(text).domain()
            require(ticket.snapshot.ticketId == ticketId && ticket.status == TicketStatus.CANCELLED)
            TicketScope.entries.forEach { refreshMyTickets(it) }
            if(!auth.isCurrentPublicRead(ctx)) CancelTicketOutcome.Failed(TicketError.SESSION_CHANGED) else CancelTicketOutcome.Cancelled(ticket)
        } catch(e: RemoteFailure) { CancelTicketOutcome.Failed(if(!auth.isCurrentPublicRead(ctx)) TicketError.SESSION_CHANGED else if(e.httpStatus == 404) TicketError.NOT_FOUND else if(e.machineCode == "TICKET_USED") TicketError.USED else
            if(e.error == AuthError.Unauthorized) TicketError.AUTHENTICATION_REQUIRED else TicketError.valueOf(e.readError().name)) }
          catch(_: SerializationException) { CancelTicketOutcome.Failed(TicketError.PROTOCOL) }
          catch(_: IllegalArgumentException) { CancelTicketOutcome.Failed(TicketError.PROTOCOL) }
          catch(_: java.time.DateTimeException) { CancelTicketOutcome.Failed(TicketError.PROTOCOL) }
    }
}
internal fun RemoteFailure.readError(): ProductReadError = when(error) {
    AuthError.Network -> ProductReadError.NETWORK; AuthError.Timeout -> ProductReadError.TIMEOUT
    AuthError.Forbidden -> ProductReadError.FORBIDDEN; AuthError.ServerUnavailable -> ProductReadError.SERVER_UNAVAILABLE
    else -> ProductReadError.PROTOCOL
}

class RemoteTicketDocuments(private val api: AuthHttp, private val auth: RemoteAuthRepository) : DocumentsRepository {
    override suspend fun prepare(request: DocumentRequest): DocumentResult {
        if(request !is DocumentRequest.TicketPdf) return DocumentResult.Failed(DocumentError.FEATURE_UNAVAILABLE)
        if(request.ticketId.value.isBlank() || request.guestAccess?.let { it.isBlank() || it.length > 128 } == true) return DocumentResult.Failed(DocumentError.PROTOCOL)
        val ctx = auth.capturePublicRead()
        if(ctx.token == null && request.guestAccess.isNullOrBlank()) return DocumentResult.Failed(DocumentError.AUTHENTICATION_REQUIRED)
        val stamp = DocumentStamp(ctx.profile?.account?.id, ctx.snapshot.generation)
        val path = apiPath("tickets", request.ticketId.value, "pdf") + (request.guestAccess?.let {
            require(it.length <= 128)
            "?token=" + "https://path.invalid/".toHttpUrl().newBuilder().addQueryParameter("token", it).build().encodedQuery!!.substringAfter("=")
        } ?: "")
        return DocumentResult.Ready(DocumentSnapshot.Remote(stamp, request, DocumentSource { output ->
            if(!auth.isCurrentPublicRead(ctx)) throw java.io.IOException("Document owner changed")
            val bytes = try { api.downloadPdf(path, if(request.guestAccess == null) ctx.token else null) }
            catch(e: RemoteFailure) {
                if(e.error == AuthError.Unauthorized && request.guestAccess == null) auth.rejectUnauthorized(ctx)
                throw DocumentFailure(if(e.machineCode == "STORAGE_UNAVAILABLE") DocumentError.STORAGE_UNAVAILABLE else when(e.error) {
                    AuthError.Unauthorized -> DocumentError.AUTHENTICATION_REQUIRED
                    AuthError.Network -> DocumentError.NETWORK; AuthError.Timeout -> DocumentError.TIMEOUT
                    AuthError.Forbidden -> DocumentError.FORBIDDEN; AuthError.ServerUnavailable -> DocumentError.SERVER_UNAVAILABLE
                    else -> if(e.httpStatus == 404) DocumentError.NOT_FOUND else DocumentError.PROTOCOL
                })
            }
            currentCoroutineContext().ensureActive()
            if(!auth.isCurrentPublicRead(ctx)) throw java.io.IOException("Document owner changed")
            output.write(bytes)
        }))
    }
    override suspend fun validate(snapshot: DocumentSnapshot): DocumentError? {
        val ctx = auth.capturePublicRead()
        return if(ctx.snapshot.generation != snapshot.stamp.generation || ctx.profile?.account?.id != snapshot.stamp.accountId) DocumentError.SESSION_CHANGED else null
    }
    override suspend fun resendTicket(ticketId: TicketId) = ResendResult.Failed(DocumentError.FEATURE_UNAVAILABLE)
}
