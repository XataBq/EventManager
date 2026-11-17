package ru.eventpass.data.local

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.RegistrationQuestion

/** Draft definitions only: no credentials, ticket/guest payloads, registration answers or exports. */
internal object DraftCodec {
    private val json=Json { encodeDefaults=true; explicitNulls=true; ignoreUnknownKeys=false }
    private const val limit=33_554_432
    fun fields(value:EventFields):String=json.encodeToString(FieldsDto.from(value)).also { require(it.toByteArray().size<=limit) }
    fun fields(value:String):EventFields {require(value.toByteArray().size<=limit);return json.decodeFromString<FieldsDto>(value).domain()}
    fun command(value:StoredDraftCommand?):String?=value?.let { json.encodeToString(CommandDto(it.kind.name,it.id,it.body,it.fingerprint,FieldsDto.from(it.fields),it.revision,it.firstAttemptAt?.toEpochMilli())) }
    fun command(value:String?):StoredDraftCommand?=value?.let {
        require(it.toByteArray().size<=limit)
        json.decodeFromString<CommandDto>(it).let { c->StoredDraftCommand(DraftCommandKind.valueOf(c.kind),c.id,c.body,c.fingerprint,c.fields.domain(),c.revision,c.attempt?.let(Instant::ofEpochMilli)) }
    }
    fun strings(value:Set<String>):String=json.encodeToString(value.sorted())
    fun strings(value:String):Set<String> = json.decodeFromString<List<String>>(value).toSet()
    fun encode(v:StoredDraft)=DraftRow(accountId=v.accountId.value,localId=v.id.value,incarnation=v.incarnation,storageVersion=v.storageVersion,localRevision=v.localRevision,
        remoteId=v.remoteId?.value,slug=v.slug?.value,confirmed=v.confirmed?.let(::fields),base=v.base?.let(::fields),pending=v.pending?.let(::fields),
        syncState=v.state.name,command=command(v.command),remoteConflict=v.remoteConflict?.let(::fields),coverReference=v.pending?.cover?.assetId,
        problem=v.problem?.name,invalidFields=strings(v.invalidFields),retryAt=v.retryAt?.toEpochMilli(),
        serverStatus=v.serverStatus?.name,finishedAt=v.finishedAt?.toEpochMilli(),uploadedCover=v.uploadedCover?.let {json.encodeToString(CoverDto(it.assetId,it.mimeType,it.byteCount,it.remoteAssetId))})
    fun decode(v:DraftRow)=StoredDraft(UserId(v.accountId),DraftId(v.localId),v.incarnation,v.storageVersion,v.localRevision,
        v.remoteId?.let(::EventId),v.slug?.let(::EventSlug),v.confirmed?.let(::fields),v.base?.let(::fields),v.pending?.let(::fields),
        DraftSyncState.valueOf(v.syncState),command(v.command),v.remoteConflict?.let(::fields),v.problem?.let(DraftProblem::valueOf),
        strings(v.invalidFields),v.retryAt?.let(Instant::ofEpochMilli),v.serverStatus?.let(EventLifecycle::valueOf),v.finishedAt?.let(Instant::ofEpochMilli),
        v.uploadedCover?.let {json.decodeFromString<CoverDto>(it).domain()})
}

@Serializable private data class CoverDto(val id:String,val mime:String,val bytes:Long,val remote:String?) { fun domain()=EventCover(id,mime,bytes,remote) }
@Serializable private data class OptionDto(val id:String,val title:String,val hint:String?)
@Serializable private data class QuestionDto(val id:String,val title:String,val required:Boolean,val kind:String,
    val options:List<OptionDto>,val placeholder:String?,val help:String?,val rawType:String?)
@Serializable private data class SectionDto(val id:String,val title:String,val time:String)
@Serializable private data class FieldsDto(val title:String,val starts:String?,val ends:String?,val zone:String,val venue:String,val capacity:Int?,
    val questions:List<QuestionDto>,val description:String,val sections:List<SectionDto>,val cover:CoverDto?,val price:String,
    val asset:String?,val url:String?) {
    fun domain()=EventFields(title,starts?.let(Instant::parse),ends?.let(Instant::parse),zone,venue,capacity,
        questions.map {q->DraftQuestion(q.id,q.title,q.required,QuestionKind.valueOf(q.kind),q.options.map { RegistrationQuestion.Option(it.id,it.title,it.hint) },q.placeholder,q.help,q.rawType)},
        description,sections.map {EventSection(it.title,it.time,it.id)},cover?.domain(),PriceType.valueOf(price),asset,url)
    companion object {
        fun from(f:EventFields)=FieldsDto(f.title,f.startsAt?.toString(),f.endsAt?.toString(),f.zoneId,f.venue,f.capacity,
            f.questions.map {q->QuestionDto(q.id,q.title,q.required,q.kind.name,q.options.map {OptionDto(it.id,it.title,it.hint)},q.placeholder,q.help,q.rawType)},
            f.description,f.sections.map {SectionDto(it.id,it.title,it.time)},f.cover?.let {CoverDto(it.assetId,it.mimeType,it.byteCount,it.remoteAssetId)},f.priceType.name,f.remoteCoverAssetId,f.remoteCoverUrl)
    }
}
@Serializable private data class CommandDto(val kind:String,val id:String,val body:String,val fingerprint:String,val fields:FieldsDto,val revision:Long,val attempt:Long?)
