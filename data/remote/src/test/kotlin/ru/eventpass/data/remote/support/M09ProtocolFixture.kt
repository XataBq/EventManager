package ru.eventpass.data.remote

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import mockwebserver3.*
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*

/** Synthetic stateful HTTP fixture. It is not an in-memory substitute for the Room tests' database. */
internal class M09ProtocolFixture:AutoCloseable {
    val server=MockWebServer()
    val requests=CopyOnWriteArrayList<Pair<String,String>>()
    var intercept:((RecordedRequest)->MockResponse?)?=null
    var user=user("actor","owner@example.test")
    var event=event()
    val creates=AtomicInteger();val patches=AtomicInteger();val uploads=AtomicInteger();val scans=AtomicInteger()
    private val createKeys=mutableMapOf<String,Pair<String,JsonObject>>()
    private val scanKeys=mutableMapOf<String,JsonObject>();private val used=mutableSetOf<String>()
    val rows=mutableListOf<JsonObject>()
    val volunteers=mutableListOf<JsonObject>()
    var revision=1;var stalePages=0;var failNextRead=0;var failCreateAck=false;var failPatchAck=false;var failScanAck=false
    var onCreate:()->Unit={};var onUpload:()->Unit={}
    var coverByteCount=4L
    val cleanup=mutableListOf<()->Unit>()
    init {
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.url.encodedPath;val method=request.method
            intercept?.invoke(request)?.let {return it}
            val body=request.body?.utf8().orEmpty();requests+=method+" "+path to body
            return when {
                path.endsWith("/auth/login") || path.endsWith("/auth/register")->json(buildJsonObject {put("token","test-token");put("user",user)})
                path.endsWith("/auth/logout")->json("{}")
                path=="/api/me" && method=="GET"->json(user)
                path=="/api/me" && method=="PATCH"->{user=JsonObject(user+Json.parseToJsonElement(body).jsonObject);json(user)}
                path=="/api/me" && method=="DELETE"->empty()
                path=="/api/events" && method=="POST"->{
                    onCreate()
                    val key=request.headers["Idempotency-Key"].orEmpty();val previous=createKeys[key]
                    if(previous!=null && previous.first!=body) return json("""{"code":"IDEMPOTENCY_CONFLICT"}""",409)
                    if(previous==null) {creates.incrementAndGet();event=JsonObject(event()+Json.parseToJsonElement(body).jsonObject.filterKeys {it!="publish"});createKeys[key]=body to event}
                    if(failCreateAck) {failCreateAck=false;json("{}",503)} else json(createKeys.getValue(key).second,201)
                }
                path.startsWith("/api/events/by-id/")->{if(failNextRead>0) {failNextRead--;json("{}",503)} else json(event)}
                path=="/api/events/server-event" && method=="PATCH"->{
                    patches.incrementAndGet();event=JsonObject(event+Json.parseToJsonElement(body).jsonObject);revision++
                    if(failPatchAck) {failPatchAck=false;json("{}",503)} else json(event)
                }
                path.endsWith("/publish")->{event=JsonObject(event+("status" to JsonPrimitive("published")));json(event)}
                path=="/api/assets/event-covers"->{onUpload();val id="asset-"+uploads.incrementAndGet();json("""{"assetId":"$id","url":"/api/assets/event-covers/$id","mimeType":"image/png","byteCount":$coverByteCount,"width":1,"height":1}""")}
                path.endsWith("/scanner")->json(buildJsonObject {put("event",event);put("viewerRole","organizer");put("checkedByMe",scans.get());put("recent",buildJsonArray {})})
                path.endsWith("/scan")->{
                    val key=request.headers["Idempotency-Key"].orEmpty();val code=Json.parseToJsonElement(body).jsonObject.getValue("code").jsonPrimitive.content
                    val previous=scanKeys[key]
                    if(previous!=null) return json(JsonObject(previous+("replayed" to JsonPrimitive(true))))
                    val decision=if(code=="invalid") "invalid" else if(!used.add(code)) "already_used" else "allowed"
                    if(decision=="allowed") {scans.incrementAndGet();revision++}
                    val receipt=buildJsonObject {put("result",decision);put("scanId",key);put("replayed",false)};scanKeys[key]=receipt
                    if(failScanAck) {failScanAck=false;json("{}",503)} else json(receipt)
                }
                path.endsWith("/volunteers") && method=="GET"->json(JsonArray(volunteers.toList()))
                path.endsWith("/volunteers") && method=="POST"->{val v=Json.parseToJsonElement("""{"userId":"volunteer-user","publicId":"654321","fullName":"Volunteer","email":"volunteer@example.test","grantedAt":"2026-11-17T10:00:00Z","checkedInCount":0}""").jsonObject
                    volunteers.removeAll {it["userId"]==v["userId"]};volunteers+=v;json(v,201)}
                path.contains("/volunteers/") && method=="DELETE"->{val id=path.substringAfterLast('/');volunteers.removeAll {it["userId"]?.jsonPrimitive?.content==id};empty()}
                path.endsWith("/stats")->json("""{"revision":"r$revision","registered":${rows.count {it["status"]?.jsonPrimitive?.content!="cancelled"}},"capacity":50,"checkedIn":${rows.count {it["status"]?.jsonPrimitive?.content=="present"}},"notCheckedIn":${rows.count {it["status"]?.jsonPrimitive?.content=="expected"}}}""")
                path.endsWith("/attendees")-> {
                    if(stalePages>0) {stalePages--;return json("""{"code":"STALE_PAGE"}""",409)}
                    if(request.url.queryParameter("revision")!="r$revision") return json("""{"code":"STALE_PAGE"}""",409)
                    val search=request.url.queryParameter("search").orEmpty();val page=request.url.queryParameter("page")!!.toInt();val size=request.url.queryParameter("pageSize")!!.toInt()
                    val matches=rows.filter {it["status"]?.jsonPrimitive?.content!="cancelled" && it["fullName"]!!.jsonPrimitive.content.contains(search,true)}
                    json(buildJsonObject {put("revision","r$revision");put("items",JsonArray(matches.drop((page-1)*size).take(size)));put("total",matches.size);put("page",page);put("pageSize",size)})
                }
                path.endsWith("/cancel") && path.contains("/attendees/")-> {
                    val id=path.substringBeforeLast('/').substringAfterLast('/');val index=rows.indexOfFirst {it["ticketId"]!!.jsonPrimitive.content==id}
                    if(index<0) return json("""{"code":"NOT_FOUND"}""",404)
                    if(rows[index]["status"]!!.jsonPrimitive.content=="present") return json("""{"code":"TICKET_USED"}""",409)
                    rows[index]=JsonObject(rows[index]+("status" to JsonPrimitive("cancelled")));revision++
                    json("""{"ticketId":"$id","status":"cancelled"}""")
                }
                else->{System.err.println("Unhandled synthetic fixture: $method $path");json("""{"code":"NOT_FOUND"}""",404)}
            }
        }}
        server.start()
    }
    val api=AuthHttp(server.url("/api/").toString())
    var secret:AuthSecret?=null
    val store=object:SessionTokenStore {override suspend fun read()=secret;override suspend fun write(token:AuthSecret){secret=token};override suspend fun clear(){secret=null}}
    fun auth(purge:suspend(UserId)->Unit={} )=RemoteAuthRepository(api,store,purge)
    suspend fun login(auth:RemoteAuthRepository){check(auth.signIn("owner@example.test",AuthSecret("test-password")) is AuthResult.Success)}
    fun published() {val start=Instant.now().minusSeconds(3600);event=event().let {JsonObject(it+("status" to JsonPrimitive("published"))+("startsAt" to JsonPrimitive(start.toString()))+("effectiveEndsAt" to JsonPrimitive(start.plusSeconds(14400).toString()))+("capacity" to JsonPrimitive(50)))}}
    fun populate(count:Int) {repeat(count) {i->rows+=Json.parseToJsonElement("""{"ticketId":"ticket-$i","ticketCode":"EVT-${i.toString().padStart(6,'0')}","fullName":"Person $i","email":"p$i@example.test","status":"expected","checkedInAt":null,"answers":{"bool":false,"text":"Unicode 李","multiple":["one","two"]}}""").jsonObject}}
    override fun close(){cleanup.forEach {it()};server.close()}
    companion object {
        fun user(id:String="actor",email:String="owner@example.test")=Json.parseToJsonElement("""{"id":"$id","publicId":"123456","fullName":"Owner","email":"$email","phone":null,"organization":null,"emailVerified":false,"notificationsEnabled":true}""").jsonObject
        fun event()=Json.parseToJsonElement("""{"id":"server-event","slug":"server-slug","organizerId":"actor","title":"Draft","status":"draft","startsAt":null,"endsAt":null,"effectiveEndsAt":null,"finishedAt":null,"location":"Hall","capacity":null,"registeredCount":0,"checkedInCount":0,"description":"","priceType":"free","questions":[],"sections":[],"coverAssetId":null,"coverUrl":null}""").jsonObject
        fun json(body:JsonElement,status:Int=200)=json(body.toString(),status)
        fun json(body:String,status:Int=200)=MockResponse.Builder().code(status).addHeader("Content-Type","application/json").body(body).build()
        fun empty()=MockResponse.Builder().code(204).build()
        val unavailable=object:EventRepository {
            override fun observePublicEvent(reference:PublicEventReference)=emptyFlow<PublicEventResult>()
            override suspend fun refreshPublicEvent(reference:PublicEventReference){}
            override fun observeMyEvents()=emptyFlow<MyEventsResult>()
            override fun observeEditor(target:EventEditorTarget)=emptyFlow<EventEditorResult>()
            override suspend fun refreshMyEvents(){}
            override suspend fun saveDraft(id:DraftId,expectedRevision:Long?,fields:EventFields)=EventEditResult.Failed(EventEditError.FeatureUnavailable)
            override suspend fun publishDraft(id:DraftId,expectedRevision:Long?)=EventEditResult.Failed(EventEditError.FeatureUnavailable)
            override suspend fun updateEvent(id:EventId,expectedRevision:Long?,expectedLocalSchemaRevision:Int?,fields:EventFields)=EventEditResult.Failed(EventEditError.FeatureUnavailable)
            override suspend fun finishEvent(id:EventId,expectedRevision:Long?)=EventEditResult.Failed(EventEditError.FeatureUnavailable)
        }
    }
}
