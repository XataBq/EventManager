package ru.eventpass.data.fake.product

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*
import ru.eventpass.core.domain.checkin.*

/** Closed fake-server data. Never used to recover a guest UI flow by email or ticket ID. */
internal class LegacyProductIncompatible(val original: ByteArray) : IllegalArgumentException("Legacy demo data require explicit reset")

internal object LegacyProductSnapshotCodec {
    fun restore(bytes: ByteArray, seed: FakeProductState): FakeProductState {
        require(bytes.size <= 8_388_608)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            if (version == 1 || version == AuthSnapshotCodec.CURRENT_MAGIC) {
                val (accounts, auth) = AuthSnapshotCodec.decode(bytes)
                return seed.copy(accounts = accounts, auth = auth)
            }
            require(version in 2..5) { "Unsupported product snapshot" }
            val authLength = input.readInt().also { require(it in 1..1_048_576 && it <= input.available()) }
            val authBytes = ByteArray(authLength).also(input::readFully)
            val (accounts, auth) = AuthSnapshotCodec.decode(authBytes)
            val nextId = input.readLong().also { require(it > 0) }
            val policy = RegistrationPolicy(FieldRequirement.valueOf(input.readUTF()))
            fun count() = input.readInt().also { require(it in 0..10_000) }
            var incompatible = false
            var events = List(count()) {
                val id = EventId(input.readUTF()); val owner = UserId(input.readUTF()); val title = input.readUTF()
                val starts = Instant.parse(input.readUTF()); val ends = input.readUTF().takeIf(String::isNotEmpty)?.let(Instant::parse)
                val zone = input.readUTF().also(java.time.ZoneId::of); val venue = input.readUTF()
                val capacity = input.readInt().also { require(it >= -1) }.takeIf { it >= 0 }
                val lifecycle = EventLifecycle.valueOf(input.readUTF()); val open = input.readBoolean(); val form = input.readInt()
                val description = input.readUTF(); val sections = List(count()) { index -> EventSection(input.readUTF(), input.readUTF(), "legacy-local-${id.value}-$index") }
                val questions = List(count()) {
                    val qid = input.readUTF(); val qtitle = input.readUTF(); val required = input.readBoolean()
                    when (input.readInt()) {
                        0 -> RegistrationQuestion.Text(qid, qtitle, required)
                        1 -> RegistrationQuestion.Checkbox(qid, qtitle, required)
                        2 -> RegistrationQuestion.SingleChoice(qid, qtitle, required,
                            List(count()) { RegistrationQuestion.Option(input.readUTF(), input.readUTF()) })
                        else -> throw IllegalArgumentException("Unknown question kind")
                    }
                }
                val valid = capacity != null && capacity > 0 && lifecycle != EventLifecycle.CANCELLED && questions.none { it is RegistrationQuestion.SingleChoice && it.options.size < 2 }
                if (!valid) incompatible = true
                Event(id, owner, title, starts, ends, zone, venue, capacity, if (valid) lifecycle else EventLifecycle.DRAFT,
                    open, form, questions, description, sections, revision = 1, slug = EventSlug("demo-${id.value}"))
            }
            val registrations = List(count()) {
                val id = input.readUTF(); val event = EventId(input.readUTF()); val user = input.readUTF().takeIf(String::isNotEmpty)?.let(::UserId)
                val email = input.readUTF(); val name = input.readUTF(); val phone = input.readUTF().takeIf(String::isNotEmpty)
                val form = input.readInt(); val accepted = Instant.parse(input.readUTF())
                val answers = buildMap {
                    repeat(count()) {
                        val key = input.readUTF(); require(key !in this)
                        put(key, when (input.readInt()) {
                            0 -> Answer.Text(input.readUTF())
                            1 -> Answer.Checkbox(input.readBoolean())
                            2 -> Answer.SingleChoice(input.readUTF())
                            else -> throw IllegalArgumentException("Unknown answer kind")
                        })
                    }
                }
                Registration(id, event, user, email, name, phone, form, answers, accepted)
            }
            val tickets = List(count()) {
                val registration = input.readUTF(); val status = TicketStatus.valueOf(input.readUTF())
                val snapshot = GuestTicketSnapshot(TicketId(input.readUTF()), EventId(input.readUTF()), input.readUTF(),
                    Instant.parse(input.readUTF()), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
                Ticket(snapshot, registration, status)
            }
            val grants = List(count()) { ScanGrant(EventId(input.readUTF()), UserId(input.readUTF()), input.readBoolean()) }
            val drafts = if (version >= 3) {
                val metadata = List(count()) {
                    Triple(EventId(input.readUTF()), input.readLong().also { require(it > 0) }, input.cover())
                }
                require(metadata.map { it.first }.toSet() == events.map { it.id }.toSet() && metadata.size == events.size)
                events = events.map { e -> metadata.single { it.first == e.id }.let { e.copy(revision = it.second, cover = it.third) } }
                List(count()) {
                    val id = DraftId(input.readUTF().also { require(EventEditingPolicy.validId(it)) })
                    val owner = UserId(input.readUTF()).also { require(it in accounts) }
                    val revision = input.readLong().also { require(it > 0) }
                    val published = input.readUTF().takeIf(String::isNotEmpty)?.let(::EventId)
                    require(published == null || events.any { it.id == published && it.ownerId == owner })
                    EventDraft(id, owner, revision, input.fields(), published)
                }.also { require(it.map { d -> d.id }.distinct().size == it.size) }
            } else emptyList()
            val checkIns = if (version >= 4) {
                val codes = List(count()) { TicketId(input.readUTF()) to ManualCode(input.readUTF()) }
                require(codes.size == tickets.size && codes.map { it.first }.toSet() == tickets.map { it.snapshot.ticketId }.toSet())
                require(codes.all { (id, code) -> tickets.single { it.snapshot.ticketId == id }.snapshot.manualCode == code })
                List(count()) { CheckInRecord(input.readUTF(), EventId(input.readUTF()), TicketId(input.readUTF()), UserId(input.readUTF()),
                    Instant.parse(input.readUTF()), input.readUTF(), input.readUTF()) }.also { records ->
                    require(records.map { it.id }.distinct().size == records.size && records.map { it.ticketId }.distinct().size == records.size)
                    require(records.all { r -> r.checkerId in accounts && tickets.any { t -> t.snapshot.ticketId == r.ticketId && t.snapshot.eventId == r.eventId && t.status == TicketStatus.USED } })
                }
            } else emptyList()
            val profiles = if (version >= 5) buildMap<UserId, ProfileDetails> {
                repeat(count()) {
                    val id = UserId(input.readUTF()); require(id in accounts && id !in this)
                    val phone = input.readUTF().takeIf(String::isNotEmpty); val org = input.readUTF().takeIf(String::isNotEmpty)
                    val notifications = input.readBoolean()
                    require(phone == null || RegistrationValidator.normalizeRussianPhone(phone) == phone)
                    require(org == null || org.length <= 200 && org.none(Char::isISOControl))
                    put(id, ProfileDetails(phone, org, notifications))
                }
            } else emptyMap()
            require(input.available() == 0)
            require(events.map { it.id }.distinct().size == events.size)
            require(registrations.map { it.id }.distinct().size == registrations.size)
            require(tickets.map { it.snapshot.ticketId }.distinct().size == tickets.size)
            require(registrations.all { r -> events.any { it.id == r.eventId } })
            require(tickets.all { t -> registrations.any { it.id == t.registrationId && it.eventId == t.snapshot.eventId } })
            require(tickets.map { it.snapshot.manualCode }.distinct().size == tickets.size)
            require(grants.all { it.userId in accounts && events.any { e -> e.id == it.eventId } })
            require(grants.map { it.eventId to it.userId }.distinct().size == grants.size)
            if (drafts.any { EventEditingPolicy.storageErrors(it.fields).isNotEmpty() }) incompatible = true
            if (events.any { it.title.length > 300 || it.venue.length > 300 || it.questions.size > 50 }) incompatible = true
            if (incompatible) throw LegacyProductIncompatible(bytes.copyOf())
            return seed.copy(accounts = accounts, auth = auth, events = events.associateBy { it.id },
                registrations = registrations, tickets = tickets, grants = grants, policy = policy, nextId = nextId,
                drafts = drafts.associateBy { it.id }, checkIns = checkIns, profileDetails = profiles)
        }
    }

    private fun DataOutputStream.cover(value: EventCover?) {
        writeBoolean(value != null)
        value?.let { writeUTF(it.assetId); writeUTF(it.mimeType); writeLong(it.byteCount) }
    }
    private fun DataInputStream.cover(): EventCover? = if (readBoolean()) EventCover(readUTF(), readUTF(), readLong()) else null
    private fun DataOutputStream.fields(f: EventFields) {
        writeUTF(f.title); writeUTF(f.startsAt?.toString().orEmpty()); writeUTF(f.endsAt?.toString().orEmpty())
        writeUTF(f.zoneId); writeUTF(f.venue); writeBoolean(f.capacity != null); f.capacity?.let(::writeInt)
        writeUTF(f.description); writeInt(f.sections.size); f.sections.forEach { writeUTF(it.title); writeUTF(it.time) }
        cover(f.cover); writeInt(f.questions.size)
        f.questions.forEach { q ->
            writeUTF(q.id); writeUTF(q.title); writeBoolean(q.required); writeUTF(q.kind.name); writeInt(q.options.size)
            q.options.forEach { writeUTF(it.id); writeUTF(it.title) }
        }
    }
    private fun DataInputStream.fields(): EventFields {
        fun count(max: Int) = readInt().also { require(it in 0..max) }
        val title = readUTF(); val start = readUTF().takeIf(String::isNotEmpty)?.let(Instant::parse)
        val end = readUTF().takeIf(String::isNotEmpty)?.let(Instant::parse); val zone = readUTF(); val venue = readUTF()
        val capacity = if (readBoolean()) readInt() else null
        val description = readUTF(); val sections = List(count(50)) { EventSection(readUTF(), readUTF()) }
        val cover = cover()
        val questions = List(count(100)) {
            val id = readUTF(); val text = readUTF(); val required = readBoolean(); val kind = QuestionKind.valueOf(readUTF())
            DraftQuestion(id, text, required, kind, List(count(100)) { RegistrationQuestion.Option(readUTF(), readUTF()) })
        }
        return EventFields(title, start, end, zone, venue, capacity, questions, description, sections, cover)

    }
}
