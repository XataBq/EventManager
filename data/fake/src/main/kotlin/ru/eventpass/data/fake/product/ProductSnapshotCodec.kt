package ru.eventpass.data.fake.product

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

/** Closed fake-server data. Never used to recover a guest UI flow by email or ticket ID. */
internal object ProductSnapshotCodec {
    fun encode(state: FakeProductState): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(2)
            val auth = AuthSnapshotCodec.encode(state.accounts, state.auth)
            out.writeInt(auth.size); out.write(auth)
            out.writeLong(state.nextId); out.writeUTF(state.policy.phone.name)
            out.writeInt(state.events.size)
            state.events.values.forEach { e ->
                out.writeUTF(e.id.value); out.writeUTF(e.ownerId.value); out.writeUTF(e.title)
                out.writeUTF(e.startsAt.toString()); out.writeUTF(e.endsAt?.toString().orEmpty())
                out.writeUTF(e.zoneId); out.writeUTF(e.venue); out.writeInt(e.capacity ?: -1)
                out.writeUTF(e.lifecycle.name); out.writeBoolean(e.registrationOpen); out.writeInt(e.formVersion)
                out.writeUTF(e.description); out.writeInt(e.sections.size)
                e.sections.forEach { out.writeUTF(it.title); out.writeUTF(it.time) }
                out.writeInt(e.questions.size)
                e.questions.forEach { q ->
                    out.writeUTF(q.id); out.writeUTF(q.title); out.writeBoolean(q.required)
                    when (q) {
                        is RegistrationQuestion.Text -> out.writeInt(0)
                        is RegistrationQuestion.Checkbox -> out.writeInt(1)
                        is RegistrationQuestion.SingleChoice -> {
                            out.writeInt(2); out.writeInt(q.options.size)
                            q.options.forEach { out.writeUTF(it.id); out.writeUTF(it.title) }
                        }
                    }
                }
            }
            out.writeInt(state.registrations.size)
            state.registrations.forEach { r ->
                out.writeUTF(r.id); out.writeUTF(r.eventId.value); out.writeUTF(r.userId?.value.orEmpty())
                out.writeUTF(r.attendeeEmail); out.writeUTF(r.attendeeName); out.writeUTF(r.phone.orEmpty())
                out.writeInt(r.formVersion); out.writeUTF(r.consentAcceptedAt.toString())
                out.writeInt(r.answers.size)
                r.answers.forEach { (id, answer) ->
                    out.writeUTF(id)
                    when (answer) {
                        is Answer.Text -> { out.writeInt(0); out.writeUTF(answer.value) }
                        is Answer.Checkbox -> { out.writeInt(1); out.writeBoolean(answer.checked) }
                        is Answer.SingleChoice -> { out.writeInt(2); out.writeUTF(answer.optionId) }
                    }
                }
            }
            out.writeInt(state.tickets.size)
            state.tickets.forEach { t ->
                val s = t.snapshot
                out.writeUTF(t.registrationId); out.writeUTF(t.status.name)
                out.writeUTF(s.ticketId.value); out.writeUTF(s.eventId.value); out.writeUTF(s.eventTitle)
                out.writeUTF(s.startsAt.toString()); out.writeUTF(s.zoneId); out.writeUTF(s.venue)
                out.writeUTF(s.attendeeName); out.writeUTF(s.displayNumber); out.writeUTF(s.qrPayload)
            }
            out.writeInt(state.grants.size)
            state.grants.forEach { out.writeUTF(it.eventId.value); out.writeUTF(it.userId.value); out.writeBoolean(it.active) }
        }
    }.toByteArray().also { require(it.size <= 8_388_608) }

    fun restore(bytes: ByteArray, seed: FakeProductState): FakeProductState {
        require(bytes.size <= 8_388_608)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            if (version == 1) {
                val (accounts, auth) = AuthSnapshotCodec.decode(bytes)
                return seed.copy(accounts = accounts, auth = auth)
            }
            require(version == 2) { "Unsupported product snapshot" }
            val authLength = input.readInt().also { require(it in 1..1_048_576 && it <= input.available()) }
            val authBytes = ByteArray(authLength).also(input::readFully)
            val (accounts, auth) = AuthSnapshotCodec.decode(authBytes)
            val nextId = input.readLong().also { require(it > 0) }
            val policy = RegistrationPolicy(FieldRequirement.valueOf(input.readUTF()))
            fun count() = input.readInt().also { require(it in 0..10_000) }
            val events = List(count()) {
                val id = EventId(input.readUTF()); val owner = UserId(input.readUTF()); val title = input.readUTF()
                val starts = Instant.parse(input.readUTF()); val ends = input.readUTF().takeIf(String::isNotEmpty)?.let(Instant::parse)
                val zone = input.readUTF().also(java.time.ZoneId::of); val venue = input.readUTF()
                val capacity = input.readInt().also { require(it >= -1) }.takeIf { it >= 0 }
                val lifecycle = EventLifecycle.valueOf(input.readUTF()); val open = input.readBoolean(); val form = input.readInt()
                val description = input.readUTF(); val sections = List(count()) { EventSection(input.readUTF(), input.readUTF()) }
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
                Event(id, owner, title, starts, ends, zone, venue, capacity, lifecycle, open, form, questions, description, sections)
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
            require(input.available() == 0)
            require(events.map { it.id }.distinct().size == events.size)
            require(registrations.map { it.id }.distinct().size == registrations.size)
            require(tickets.map { it.snapshot.ticketId }.distinct().size == tickets.size)
            require(registrations.all { r -> events.any { it.id == r.eventId } })
            require(tickets.all { t -> registrations.any { it.id == t.registrationId && it.eventId == t.snapshot.eventId } })
            return seed.copy(accounts = accounts, auth = auth, events = events.associateBy { it.id },
                registrations = registrations, tickets = tickets, grants = grants, policy = policy, nextId = nextId)
        }
    }
}
