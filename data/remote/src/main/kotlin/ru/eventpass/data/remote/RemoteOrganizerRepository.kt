package ru.eventpass.data.remote

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.AuthError
import ru.eventpass.core.domain.event.*

/** Online owner operations. All identities and create receipts here are transient, never an offline queue. */
class RemoteOrganizerRepository(private val api: AuthHttp, private val auth: RemoteAuthRepository,
    unavailable: EventRepository) : EventRepository by unavailable {
    private data class Stamped<T>(val context: PublicReadContext, val result: T)
    private class Resource<T> {
        val lock = Mutex()
        val state = MutableStateFlow<Stamped<T>?>(null)
    }
    private data class Create(val context: PublicReadContext, val fingerprint: String, val key: String, val createdAt: Instant, val retryAt: Instant? = null)
    private val editors = mutableMapOf<String, Resource<EventEditorResult>>()
    private val lists = mutableMapOf<MyEventsTab, Resource<MyEventsResult>>()
    private val fresh = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val identities = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Create>()
    private val uncertain = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var lastContext: PublicReadContext? = null
    private fun discardStale(c: PublicReadContext) = synchronized(editors) {
        if(!auth.isCurrentPublicRead(c)) return@synchronized
        val old = lastContext
        if(old != null && !auth.isCurrentPublicRead(old)) {
            editors.values.forEach { it.state.value = null }
            synchronized(lists) { lists.values.forEach { it.state.value = null } }
            identities.clear(); pending.clear(); uncertain.clear()
        }
        lastContext = c
    }
    private fun editor(id: String) = synchronized(editors) { editors.getOrPut(id) { Resource() } }
    private fun list(tab: MyEventsTab) = synchronized(lists) { lists.getOrPut(tab) { Resource() } }
    override fun beginDraft(id: DraftId) { synchronized(fresh) { fresh.add(id.value) } }
    private fun id(target: EventEditorTarget) = when(target) {
        is EventEditorTarget.Draft -> target.id.value
        is EventEditorTarget.Published -> target.id.value
        is EventEditorTarget.RemoteDraft -> target.id.value
    }
    private fun path(vararg parts: String) = "https://path.invalid/".toHttpUrl().newBuilder().apply {
        parts.forEach { addPathSegment(it) }
    }.build().encodedPath.removePrefix("/")
    private suspend fun context(): PublicReadContext = auth.capturePublicRead().also {
        discardStale(it)
        if(it.token == null || it.profile == null) throw RemoteFailure(AuthError.Unauthorized)
    }
    private suspend fun request(path: String, c: PublicReadContext, body: String? = null, method: String? = null, key: String? = null): String {
        val text = auth.requestScoped(path,c,body,key,method) ?: throw RemoteFailure(AuthError.SessionChanged)
        currentCoroutineContext().ensureActive()
        return text
    }
    private fun error(e: Exception): EventEditError = when(e) {
        is RemoteFailure -> when(e.error) {
            is AuthError.Cooldown -> EventEditError.RateLimited(e.error.retryAt)
            AuthError.Network -> EventEditError.Network
            AuthError.Timeout -> EventEditError.Timeout
            AuthError.Unauthorized -> EventEditError.AuthenticationRequired
            AuthError.SessionChanged -> EventEditError.SessionChanged
            AuthError.Forbidden -> EventEditError.Forbidden
            AuthError.StorageUnavailable -> EventEditError.StorageUnavailable
            AuthError.ServerUnavailable -> EventEditError.ServerUnavailable
            else -> when {
                e.httpStatus == 404 -> EventEditError.NotFound
                e.machineCode == "EVENT_FINISHED" -> EventEditError.EventFinished
                e.machineCode == "IDEMPOTENCY_CONFLICT" -> EventEditError.Conflict
                e.fields.isNotEmpty() -> EventEditError.Validation(e.fields.keys.map { field ->
                    when(val root=field.substringBefore('.').substringBefore('[')) { "location" -> "venue"; "coverAssetId" -> "cover"; else -> root }
                }.toSet())
                e.httpStatus == 422 -> EventEditError.Validation(setOf("event"))
                else -> EventEditError.Protocol
            }
        }
        else -> EventEditError.Protocol
    }
    private suspend fun <T> outcome(block: suspend () -> T): EventEditResult<T> = try {
        EventEditResult.Saved(block())
    } catch(e: CancellationException) { throw e } catch(e: Exception) { EventEditResult.Failed(error(e)) }

    override fun observeEditor(target: EventEditorTarget): Flow<EventEditorResult> = channelFlow<Stamped<EventEditorResult>> {
        val key = id(target); val resource = editor(key)
        auth.observeSession().collectLatest {
            val c = auth.capturePublicRead()
            discardStale(c)
            if(c.token == null) { resource.state.value = null; send(Stamped(c,EventEditorResult.Failed(EventEditError.AuthenticationRequired))) }
            else {
                refreshEditor(target)
                resource.state.filterNotNull().collect { s -> send(s) }
            }
        }
    }.map { saved -> if(auth.isCurrentPublicRead(saved.context)) saved.result else EventEditorResult.Failed(EventEditError.SessionChanged) }
    override suspend fun refreshEditor(target: EventEditorTarget) {
        val key = id(target); val resource = editor(key)
        resource.lock.withLock {
            val c = auth.capturePublicRead()
            discardStale(c)
            val result = try {
                if(c.token == null) throw RemoteFailure(AuthError.Unauthorized)
                val serverId = identities[key] ?: key
                if(key in fresh && !identities.containsKey(key)) EventEditorResult.Failed(EventEditError.NotFound)
                else {
                    val dto = api.json.decodeFromString<EventDto>(request(path("events","by-id",serverId),c))
                    val value = mapEditor(dto,c)
                    uncertain.remove(key)
                    value
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { EventEditorResult.Failed(error(e)) }
            if(auth.isCurrentPublicRead(c)) resource.state.value = Stamped(c,result)
        }
    }
    override fun observeMyEvents() = observeMyEvents(MyEventsTab.ORGANIZING)
    override fun observeMyEvents(tab: MyEventsTab): Flow<MyEventsResult> = channelFlow<Stamped<MyEventsResult>> {
        val resource = list(tab)
        auth.observeSession().collectLatest {
            refreshMyEvents(tab)
            resource.state.filterNotNull().collect { s -> send(s) }
        }
    }.map { saved -> if(auth.isCurrentPublicRead(saved.context)) saved.result else MyEventsResult.Failed(EventEditError.SessionChanged) }
    override suspend fun refreshMyEvents() = refreshMyEvents(MyEventsTab.ORGANIZING)
    override suspend fun refreshMyEvents(tab: MyEventsTab) {
        val resource = list(tab)
        resource.lock.withLock {
            val c = auth.capturePublicRead()
            discardStale(c)
            val value = try {
                if(c.token == null) throw RemoteFailure(AuthError.Unauthorized)
                val dto = api.json.decodeFromString<MyEventsDto>(request("me/events?tab=${tab.name.lowercase(java.util.Locale.ROOT)}",c))
                MyEventsResult.Ready(emptyList(),emptyList(),emptyList(),Instant.now(),tab,
                    dto.items.map { it.domain() },dto.roles.toSet(),dto.counters.mapKeys { MyEventsTab.valueOf(it.key.uppercase(java.util.Locale.ROOT)) })
            } catch(e: CancellationException) { throw e } catch(e: Exception) { MyEventsResult.Failed(error(e)) }
            if(auth.isCurrentPublicRead(c)) resource.state.value = Stamped(c,value)
        }
    }
    override suspend fun saveDraft(id: DraftId, expectedRevision: Long?, fields: EventFields): EventEditResult<EventDraft> = outcome {
        save(id.value,fields) as EventEditorResult.Draft
    }.let { r -> when(r) { is EventEditResult.Saved -> EventEditResult.Saved(r.value.draft); is EventEditResult.Failed -> r } }
    override suspend fun updateEvent(id: EventId, expectedRevision: Long?, expectedLocalSchemaRevision: Int?, fields: EventFields): EventEditResult<Event> = outcome {
        (save(id.value,fields) as EventEditorResult.Published).event
    }
    private suspend fun save(key: String, fields: EventFields): EventEditorResult {
        val c = context(); val resource = editor(key)
        return resource.lock.withLock {
            if(!auth.isCurrentPublicRead(c)) throw RemoteFailure(AuthError.SessionChanged)
            val invalid=EventEditingPolicy.storageErrors(fields)
            if(invalid.isNotEmpty()) throw RemoteFailure(AuthError.Protocol,422,fields=invalid.associateWith { "Invalid" })
            val old = resource.state.value?.takeIf { auth.isCurrentPublicRead(it.context) }?.result
            val creating = key in fresh && !identities.containsKey(key)
            val full = encodeFields(fields)
            val body: String
            val text: String
            if(creating) {
                body = JsonObject(full + ("publish" to JsonPrimitive(false))).toString()
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
                val previous = pending[key]
                if(previous != null && (!auth.isCurrentPublicRead(previous.context) || previous.fingerprint != fingerprint || Instant.now() >= previous.createdAt.plusSeconds(86400)))
                    throw RemoteFailure(AuthError.Protocol)
                if(previous == null && pending.size >= 32) throw RemoteFailure(AuthError.Protocol)
                val command = previous ?: Create(c,fingerprint,UUID.randomUUID().toString(),Instant.now()).also { pending[key] = it }
                command.retryAt?.let { if(Instant.now() < it) throw RemoteFailure(AuthError.Cooldown(it)) }
                try { text = request("events",c,body,key=command.key) }
                catch(e: RemoteFailure) {
                    if(e.error is AuthError.Cooldown) pending[key] = command.copy(retryAt=e.error.retryAt)
                    if(e.error !in setOf(AuthError.Network,AuthError.Timeout,AuthError.ServerUnavailable,AuthError.Protocol) && e.error !is AuthError.Cooldown) pending.remove(key)
                    throw e
                }
            } else {
                if(key in uncertain) throw RemoteFailure(AuthError.Protocol)
                val before = when(old) {
                    is EventEditorResult.Draft -> old.draft.fields
                    is EventEditorResult.Published -> old.event.toFields()
                    else -> throw RemoteFailure(AuthError.Protocol)
                }
                val original = encodeFields(before)
                body = JsonObject(full.filter { (k,v) -> original[k] != v }).toString()
                try { text = request(path("events",identities[key] ?: key),c,body,method="PATCH") }
                catch(e: Exception) { uncertain.add(key); throw e }
            }
            val dto: EventDto
            val value: EventEditorResult
            try { dto = api.json.decodeFromString<EventDto>(text); value = mapEditor(dto,c) }
            catch(e: Exception) { if(!creating) uncertain.add(key); throw e }
            if(!auth.isCurrentPublicRead(c)) throw RemoteFailure(AuthError.SessionChanged)
            identities[key] = dto.id; pending.remove(key)
            resource.state.value = Stamped(c,value)
            editor(dto.id).state.value = Stamped(c,value)
            value
        }
    }
    override suspend fun publishDraft(id: DraftId, expectedRevision: Long?): EventEditResult<EventId> = outcome {
        val result = action(id.value,"publish") as EventEditorResult.Published
        result.event.id
    }
    override suspend fun finishEvent(id: EventId, expectedRevision: Long?): EventEditResult<Event> = outcome {
        (action(id.value,"finish") as EventEditorResult.Published).event
    }
    private suspend fun action(key: String, action: String): EventEditorResult {
        val c = context(); val resource = editor(key)
        return resource.lock.withLock {
            if(key in uncertain) throw RemoteFailure(AuthError.Protocol)
            try {
                val dto = api.json.decodeFromString<EventDto>(request(path("events",identities[key] ?: key,action),c,"{}"))
                val result = mapEditor(dto,c)
                if(!auth.isCurrentPublicRead(c)) throw RemoteFailure(AuthError.SessionChanged)
                resource.state.value = Stamped(c,result); editor(dto.id).state.value = Stamped(c,result)
                result
            } catch(e: Exception) { uncertain.add(key); throw e }
        }
    }
    internal fun mapEditor(dto: EventDto, c: PublicReadContext): EventEditorResult {
        require(dto.organizerId == c.profile?.account?.id?.value)
        if(dto.status == "draft") return EventEditorResult.Draft(EventDraft(DraftId(dto.id),UserId(dto.organizerId),null,
            EventFields(dto.title,dto.startsAt?.let(Instant::parse),dto.endsAt?.let(Instant::parse),venue=dto.location,
                capacity=dto.capacity,description=dto.description,priceType=PriceType.valueOf(dto.priceType.uppercase(java.util.Locale.ROOT)),
                questions=dto.questions.map { q -> DraftQuestion(q.id,q.label,q.required,when(q.type) {
                    "choice" -> QuestionKind.SINGLE_CHOICE; "select" -> QuestionKind.SELECT; "multiple" -> QuestionKind.MULTIPLE
                    else -> QuestionKind.entries.firstOrNull { it.name.equals(q.type,true) } ?: QuestionKind.UNSUPPORTED
                }, q.options.orEmpty().map { ru.eventpass.core.domain.registration.RegistrationQuestion.Option(it.value,it.label,it.hint) },q.placeholder,q.help,q.type) },
                sections=dto.sections.map { EventSection(it.title,it.time,it.id) },remoteCoverAssetId=dto.coverAssetId,remoteCoverUrl=dto.coverUrl)))
        return EventEditorResult.Published(mapPublicEvent(PublicEventDto(dto,ViewerDto(null,true,false)),c).event,false,Instant.now(),
            terminal=dto.status in setOf("finished","cancelled") || dto.finishedAt != null)
    }
}

internal fun encodeFields(f: EventFields): JsonObject = buildJsonObject {
    put("title",f.title); put("description",f.description); put("startsAt",f.startsAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("endsAt",f.endsAt?.toString()?.let(::JsonPrimitive) ?: JsonNull); put("location",f.venue)
    put("capacity",f.capacity?.let(::JsonPrimitive) ?: JsonNull); put("priceType",f.priceType.name.lowercase(java.util.Locale.ROOT))
    put("coverAssetId",f.remoteCoverAssetId?.let(::JsonPrimitive) ?: JsonNull)
    putJsonArray("sections") { f.sections.forEach { s -> add(buildJsonObject { put("id",s.id);put("title",s.title);put("time",s.time) }) } }
    putJsonArray("questions") { f.questions.forEach { q -> add(buildJsonObject {
        put("id",q.id);put("label",q.title);put("required",q.required)
        put("type",when(q.kind) { QuestionKind.SINGLE_CHOICE -> "choice";QuestionKind.UNSUPPORTED -> q.rawType ?: "unsupported";else -> q.kind.name.lowercase(java.util.Locale.ROOT) })
        q.placeholder?.let { put("placeholder",it) };q.help?.let { put("help",it) }
        if(q.kind in setOf(QuestionKind.SINGLE_CHOICE,QuestionKind.SELECT,QuestionKind.MULTIPLE)) putJsonArray("options") {
            q.options.forEach { o -> add(buildJsonObject { put("value",o.id);put("label",o.title);o.hint?.let { put("hint",it) } }) }
        }
    }) } }
}
@Serializable internal data class MyEventsDto(val roles: List<String>,val items: List<EventSummaryDto>,val counters: Map<String,Int>)
@Serializable internal data class EventSummaryDto(val id: String,val slug: String,val title: String,val status: String,
    val startsAt: String?,val endsAt: String?,val effectiveEndsAt: String?,val finishedAt: String?,val location: String,
    val capacity: Int?,val registeredCount: Int,val checkedInCount: Int) {
    fun domain() = MyEventSummary(EventId(id),EventSlug(slug),title,EventLifecycle.valueOf(status.uppercase(java.util.Locale.ROOT)),
        startsAt?.let(Instant::parse),endsAt?.let(Instant::parse),effectiveEndsAt?.let(Instant::parse),finishedAt?.let(Instant::parse),location,capacity,registeredCount,checkedInCount)
}
