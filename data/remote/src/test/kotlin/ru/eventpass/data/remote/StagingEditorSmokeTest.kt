package ru.eventpass.data.remote

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.attendance.*

/** One synthetic owner: real multipart cover, editor, historical answer/export and delete cascade. */
class StagingEditorSmokeTest {
    private fun png():ByteArray {
        val raw=ByteArrayOutputStream()
        DeflaterOutputStream(raw).use {z->repeat(360) {z.write(0);repeat(640) {z.write(byteArrayOf(90,70,(-60).toByte(),(-1).toByte()))}}}
        val out=ByteArrayOutputStream();val data=DataOutputStream(out)
        data.write(byteArrayOf((-119).toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,bytes:ByteArray) {val name=type.toByteArray();data.writeInt(bytes.size);data.write(name);data.write(bytes);data.writeInt(CRC32().apply {update(name);update(bytes)}.value.toInt())}
        val header=ByteArrayOutputStream();DataOutputStream(header).apply {writeInt(640);writeInt(360);write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray());chunk("IDAT",raw.toByteArray());chunk("IEND",byteArrayOf())
        return out.toByteArray()
    }
    @Test fun uploadAttachEditAfterRegistrationExportHistoryAndConfirmedDelete()=runBlocking<Unit> {
        assumeTrue(System.getenv("EVENTPASS_STAGING_SMOKE")=="1")
        val evidence=Path.of("../../.gradle/m09-evidence")
        val client=okhttp3.OkHttpClient.Builder().addInterceptor {chain->
            val response=chain.proceed(chain.request())
            if(chain.request().url.encodedPath.endsWith("/assets/event-covers")) {
                val parsed=runCatching {Json.parseToJsonElement(response.peekBody(262144).string()).jsonObject}.getOrNull()
                Files.writeString(evidence.resolve("cover-upload-metadata.json"),buildJsonObject {
                    put("httpStatus",response.code)
                    for(key in listOf("code","mimeType","byteCount","width","height")) parsed?.get(key)?.let {put(key,it)}
                }.toString())
            }
            response
        }.build()
        val api=AuthHttp("https://staging.eventpass.dikanskii.ru/api/",client);var token:AuthSecret?=null
        val auth=RemoteAuthRepository(api,object:SessionTokenStore {override suspend fun read()=token;override suspend fun write(value:AuthSecret){token=value};override suspend fun clear(){token=null}})
        val email="android.m09.editor.${UUID.randomUUID()}@example.com";val password=AuthSecret(UUID.randomUUID().toString())
        val record=Path.of("../../.gradle/m09-evidence/editor-fixture.private.json")
        Files.createDirectories(record.parent);Files.writeString(record,buildJsonObject {put("email",email);put("password",password.value)}.toString())
        var deleted=false
        try {
            val signup=auth.signUp("M09 editor",email,password,true)
            assertTrue((signup as? AuthResult.Failed)?.error.toString(),signup is AuthResult.Success)
            val repo=RemoteOrganizerRepository(api,auth,M09ProtocolFixture.unavailable);val local=DraftId(UUID.randomUUID().toString());repo.beginDraft(local)
            assertTrue(repo.saveDraft(local,null,EventFields(title="M09 incomplete")) is EventEditResult.Saved)
            val bytes=png();Files.write(evidence.resolve("cover-fixture.png"),bytes)
            val cover=EventCover("synthetic-cover","image/png",bytes.size.toLong())
            val reader=object:CoverAssetReader {override suspend fun read(cover:EventCover)=bytes;override suspend fun release(cover:EventCover){}}
            val uploaded=RemoteCoverTransfer(api,auth,reader).importCover("synthetic",object:CoverImporter {override suspend fun importCover(source:String)=CoverImportResult.Imported(cover)})
            assertTrue("Real PNG cover upload",uploaded is CoverImportResult.Imported)
            val asset=(uploaded as CoverImportResult.Imported).cover.remoteAssetId!!
            val fields=EventFields(title="M09 editor ${UUID.randomUUID()}",description="Synthetic editor validation",startsAt=Instant.now().minusSeconds(60),venue="Staging",capacity=3,
                questions=listOf(DraftQuestion("historical",title="Original flag",kind=QuestionKind.CHECKBOX)),remoteCoverAssetId=asset)
            assertTrue(repo.saveDraft(local,null,fields) is EventEditResult.Saved)
            val publication=repo.publishDraft(local,null);assertTrue(publication.toString(),publication is EventEditResult.Saved)
            val id=(publication as EventEditResult.Saved).value
            val event=api.json.parseToJsonElement(api.request("events/by-id/${id.value}",token=token)).jsonObject
            assertEquals(asset,event["coverAssetId"]!!.jsonPrimitive.content)
            val issued=RemoteRegistrations(api,auth).submitAuthenticated(AuthenticatedRegistrationCommand(id,null,"ignored",answers=mapOf("historical" to Answer.Checkbox(false)),consentAccepted=true))
            assertTrue(issued is AuthenticatedRegistrationOutcome.Registered)
            assertTrue(repo.updateEvent(id,null,null,fields.copy(questions=emptyList(),remoteCoverAssetId=null)) is EventEditResult.Saved)
            val detached=api.json.parseToJsonElement(api.request("events/by-id/${id.value}",token=token)).jsonObject
            assertEquals(JsonNull,detached["coverAssetId"])
            val stats=api.json.parseToJsonElement(api.request("events/${id.value}/stats",token=token)).jsonObject
            val revision=java.net.URLEncoder.encode(stats["revision"]!!.jsonPrimitive.content,"UTF-8")
            val page=api.json.parseToJsonElement(api.request("events/${id.value}/attendees?revision=$revision&page=1&pageSize=20",token=token)).jsonObject
            assertEquals(JsonPrimitive(false),page["items"]!!.jsonArray.single().jsonObject["answers"]!!.jsonObject["historical"])
            val document=RemoteTicketDocuments(api,auth).prepare(DocumentRequest.AttendanceXlsx(id)) as DocumentResult.Ready
            val output=ByteArrayOutputStream();(document.snapshot as DocumentSnapshot.Remote).source.writeTo(output)
            val text=buildString {ZipInputStream(output.toByteArray().inputStream()).use {zip->while(true) {val entry=zip.nextEntry ?: break;if(entry.name.endsWith(".xml")) append(zip.readBytes().toString(Charsets.UTF_8))}}}
            assertTrue("Export omitted historical answer column",text.contains("historical") || text.contains("Original flag"))
            Files.write(record.parent.resolve("files/staging-history.xlsx"),output.toByteArray())
            assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Deleted);deleted=true
            val removed=runCatching {api.request("events/${event["slug"]!!.jsonPrimitive.content}")}.exceptionOrNull()
            assertEquals(404,(removed as RemoteFailure).httpStatus)
        } finally {
            if(!deleted && token!=null) {assertTrue(auth.profileRepository.deleteAccount() is DeleteAccountResult.Deleted);deleted=true}
            if(deleted) Files.deleteIfExists(record)
        }
    }
}
