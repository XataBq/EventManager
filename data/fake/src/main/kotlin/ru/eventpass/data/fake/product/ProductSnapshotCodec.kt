package ru.eventpass.data.fake.product

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.checkin.*

/** Product schema 6: bounded UTF-8, explicit local references and all supported answer types. */
internal object ProductSnapshotCodec {
    // A legal 50 x 50 option form with 300-code-point Unicode fields exceeds 8 MiB.
    internal const val MAX = 33_554_432
    fun encode(state: FakeProductState): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(6)
            val auth = AuthSnapshotCodec.encode(state.accounts, state.auth); out.writeInt(auth.size); out.write(auth)
            out.writeLong(state.nextId); out.text(state.policy.phone.name)
            out.writeInt(state.events.size)
            state.events.values.forEach { e ->
                out.text(e.id.value); out.text(e.ownerId.value); out.fields(e.toFields()); out.text(e.lifecycle.name)
                out.writeBoolean(e.registrationOpen); out.intOrNull(e.localSchemaRevision); out.longOrNull(e.revision)
                out.nullText(e.slug?.value); out.nullText(e.finishedAt?.toString())
            }
            out.writeInt(state.registrations.size)
            state.registrations.forEach { r ->
                out.text(r.id); out.text(r.eventId.value); out.nullText(r.userId?.value); out.text(r.attendeeEmail); out.text(r.attendeeName)
                out.nullText(r.phone); out.intOrNull(r.localSchemaRevision); out.text(r.consentAcceptedAt.toString()); out.writeInt(r.answers.size)
                r.answers.forEach { (id, a) -> out.text(id); when (a) {
                    is Answer.Text -> { out.writeInt(0); out.text(a.value) }
                    is Answer.Checkbox -> { out.writeInt(1); out.writeBoolean(a.checked) }
                    is Answer.SingleChoice -> { out.writeInt(2); out.text(a.optionId) }
                    is Answer.MultipleChoice -> { out.writeInt(3); out.writeInt(a.optionIds.size); a.optionIds.forEach { out.text(it) } }
                } }
                out.writeBoolean(r.questionSnapshot != null)
                r.questionSnapshot?.let { snapshot -> out.fields(EventFields(questions = snapshot.map { it.toDraftQuestion() })) }
            }
            out.writeInt(state.tickets.size)
            state.tickets.forEach { t ->
                val s=t.snapshot; out.text(requireNotNull(t.registrationId)); out.text(t.status.name); out.text(s.ticketId.value); out.text(s.eventId.value)
                out.text(s.eventTitle); out.text(s.startsAt.toString()); out.text(s.zoneId); out.text(s.venue); out.text(s.attendeeName)
                out.text(s.displayNumber); out.text(s.qrPayload); out.text(s.manualCode.value)
            }
            out.writeInt(state.grants.size); state.grants.forEach { out.text(it.eventId.value); out.text(it.userId.value); out.writeBoolean(it.active) }
            out.writeInt(state.drafts.size); state.drafts.values.forEach { d -> out.text(d.id.value); out.text(d.ownerId.value); out.writeLong(requireNotNull(d.revision)); out.nullText(d.publishedEventId?.value); out.fields(d.fields) }
            out.writeInt(state.checkIns.size); state.checkIns.forEach { c -> out.text(c.id); out.text(c.eventId.value); out.text(c.ticketId.value); out.text(c.checkerId.value); out.text(c.checkedAt.toString()); out.text(c.attendeeName); out.text(c.displayNumber) }
            out.writeInt(state.profileDetails.size); state.profileDetails.forEach { (id,p) -> out.text(id.value); out.nullText(p.phone); out.nullText(p.organization); out.writeBoolean(p.notifications) }
        }
    }.toByteArray().also { require(it.size <= MAX) }

    fun restore(bytes: ByteArray, seed: FakeProductState): FakeProductState {
        require(bytes.size <= MAX)
        val version=DataInputStream(ByteArrayInputStream(bytes)).readInt()
        if(version != 6) return LegacyProductSnapshotCodec.restore(bytes, seed)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt()==6)
            val length=input.readInt(); require(length in 1..1_048_576 && length<=input.available())
            val authBytes=ByteArray(length).also(input::readFully); val (accounts,auth)=AuthSnapshotCodec.decode(authBytes)
            val nextId=input.readLong(); require(nextId>0)
            val policy=RegistrationPolicy(FieldRequirement.valueOf(input.text()))
            val events=List(input.count()) {
                val id=EventId(input.text()); val owner=UserId(input.text()); val f=input.fields(); val lifecycle=EventLifecycle.valueOf(input.text())
                val open=input.readBoolean(); val schema=input.intOrNull(); val revision=input.longOrNull(); val slug=input.nullText()?.let(::EventSlug); val finished=input.nullText()?.let(Instant::parse)
                require(f.startsAt!=null)
                Event(id,owner,f.title,requireNotNull(f.startsAt),f.endsAt,f.zoneId,f.venue,f.capacity,lifecycle,open,schema,
                    EventEditingPolicy.questions(f),f.description,f.sections,revision,f.cover,slug,finished,f.priceType,f.remoteCoverUrl,f.remoteCoverAssetId)
            }
            val registrations=List(input.count()) {
                val id=input.text(); val event=EventId(input.text()); val user=input.nullText()?.let(::UserId); val email=input.text(); val name=input.text(); val phone=input.nullText(); val schema=input.intOrNull(); val consent=Instant.parse(input.text())
                val answers=buildMap<String,Answer> { repeat(input.count(50)) {
                    val key=input.text(); require(key !in this); put(key,when(input.readInt()) {
                        0->Answer.Text(input.text()); 1->Answer.Checkbox(input.readBoolean()); 2->Answer.SingleChoice(input.text())
                        3->Answer.MultipleChoice(List(input.count(50)){ input.text() }); else->throw IllegalArgumentException("Unknown answer kind")
                    })
                } }
                Registration(id,event,user,email,name,phone,schema,answers,consent, questionSnapshot = if (input.readBoolean()) EventEditingPolicy.questions(input.fields()) else null)
            }
            val tickets=List(input.count()) {
                val registration=input.text(); val status=TicketStatus.valueOf(input.text()); val id=TicketId(input.text()); val event=EventId(input.text()); val title=input.text(); val start=Instant.parse(input.text()); val zone=input.text(); val venue=input.text(); val name=input.text(); val number=input.text(); val qr=input.text(); val manual=ManualCode(input.text())
                Ticket(GuestTicketSnapshot(id,event,title,start,zone,venue,name,number,qr,manual),registration,status)
            }
            val grants=List(input.count()){ ScanGrant(EventId(input.text()),UserId(input.text()),input.readBoolean()) }
            val drafts=List(input.count()) { val id=DraftId(input.text()); val owner=UserId(input.text()); val revision=input.readLong(); val published=input.nullText()?.let(::EventId); EventDraft(id,owner,revision,input.fields(),published) }
            val checkIns=List(input.count()){ CheckInRecord(input.text(),EventId(input.text()),TicketId(input.text()),UserId(input.text()),Instant.parse(input.text()),input.text(),input.text()) }
            val profiles=buildMap<UserId,ProfileDetails>{ repeat(input.count()){ val id=UserId(input.text()); require(id !in this && id in accounts); put(id,ProfileDetails(input.nullText(),input.nullText(),input.readBoolean())) } }
            require(input.available()==0)
            require(events.map{it.id}.distinct().size==events.size)
            require(registrations.map{it.id}.distinct().size==registrations.size)
            require(tickets.map{it.snapshot.ticketId}.distinct().size==tickets.size)
            require(registrations.all{r->events.any{it.id==r.eventId} && (r.userId==null || r.userId in accounts)})
            require(tickets.all{t->registrations.any{it.id==t.registrationId && it.eventId==t.snapshot.eventId}})
            require(tickets.map{it.snapshot.manualCode}.distinct().size==tickets.size)
            require(grants.all{it.userId in accounts && events.any{e->e.id==it.eventId}})
            require(grants.map{it.eventId to it.userId}.distinct().size==grants.size)
            require(drafts.map{it.id}.distinct().size==drafts.size && drafts.all{it.ownerId in accounts && (it.revision ?: 0)>0 && (it.publishedEventId==null || events.any{e->e.id==it.publishedEventId && e.ownerId==it.ownerId})})
            require(checkIns.map{it.id}.distinct().size==checkIns.size && checkIns.map{it.ticketId}.distinct().size==checkIns.size)
            require(checkIns.all{c->c.checkerId in accounts && tickets.any{it.snapshot.ticketId==c.ticketId && it.snapshot.eventId==c.eventId && it.status==TicketStatus.USED}})
            return seed.copy(accounts=accounts,auth=auth,events=events.associateBy{it.id},registrations=registrations,tickets=tickets,grants=grants,drafts=drafts.associateBy{it.id},checkIns=checkIns,profileDetails=profiles,policy=policy,nextId=nextId)
        }
    }
    private fun DataOutputStream.text(value:String) { val bytes=value.toByteArray(Charsets.UTF_8); require(bytes.size<=200_000); writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.text():String { val length=readInt(); require(length in 0..200_000 && length<=available()); val bytes=ByteArray(length).also(::readFully); return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    private fun DataOutputStream.nullText(v:String?) { writeBoolean(v!=null); if(v!=null) text(v) }
    private fun DataInputStream.nullText():String?=if(readBoolean()) text() else null
    private fun DataOutputStream.intOrNull(v:Int?) { writeBoolean(v!=null); if(v!=null) writeInt(v) }
    private fun DataInputStream.intOrNull():Int?=if(readBoolean()) readInt() else null
    private fun DataOutputStream.longOrNull(v:Long?) { writeBoolean(v!=null); if(v!=null) writeLong(v) }
    private fun DataInputStream.longOrNull():Long?=if(readBoolean()) readLong() else null
    private fun DataInputStream.count(max:Int=10_000)=readInt().also { require(it in 0..max) }
    private fun DataOutputStream.fields(f:EventFields) {
        text(f.title); nullText(f.startsAt?.toString()); nullText(f.endsAt?.toString()); text(f.zoneId); text(f.venue); intOrNull(f.capacity); text(f.description)
        writeInt(f.sections.size); f.sections.forEach { text(it.id); text(it.title); text(it.time) }
        writeBoolean(f.cover!=null); f.cover?.let { text(it.assetId); text(it.mimeType); writeLong(it.byteCount) }
        text(f.priceType.name); nullText(f.remoteCoverAssetId); nullText(f.remoteCoverUrl)
        writeInt(f.questions.size); f.questions.forEach { q -> text(q.id); text(q.title); writeBoolean(q.required); text(q.kind.name); nullText(q.placeholder); nullText(q.help); nullText(q.rawType); writeInt(q.options.size); q.options.forEach { text(it.id); text(it.title); nullText(it.hint) } }
    }
    private fun DataInputStream.fields():EventFields {
        val title=text(); val start=nullText()?.let(Instant::parse); val end=nullText()?.let(Instant::parse); val zone=text(); val venue=text(); val capacity=intOrNull(); val description=text()
        val sections=List(count(50)){ val id=text(); EventSection(text(),text(),id) }
        val cover=if(readBoolean()) EventCover(text(),text(),readLong()) else null
        val price=PriceType.valueOf(text()); val asset=nullText(); val url=nullText()
        val questions=List(count(50)){ val id=text(); val label=text(); val required=readBoolean(); val kind=QuestionKind.valueOf(text()); val placeholder=nullText(); val help=nullText(); val raw=nullText(); val opts=List(count(50)){ RegistrationQuestion.Option(text(),text(),nullText()) }; DraftQuestion(id,label,required,kind,opts,placeholder,help,raw) }
        return EventFields(title,start,end,zone,venue,capacity,questions,description,sections,cover,price,asset,url).also { require(EventEditingPolicy.storageErrors(it).isEmpty()) }
    }
}
