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
internal object LegacyV4Fixture {
    fun encode(state: FakeProductState): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(4)
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
            // V3 appends editor metadata to the unchanged v2 product payload.
            out.writeInt(state.events.size)
            state.events.values.forEach { out.writeUTF(it.id.value); out.writeLong(it.revision); out.cover(it.cover) }
            out.writeInt(state.drafts.size)
            state.drafts.values.forEach { d ->
                out.writeUTF(d.id.value); out.writeUTF(d.ownerId.value); out.writeLong(d.revision)
                out.writeUTF(d.publishedEventId?.value.orEmpty()); out.fields(d.fields)
            }
            out.writeInt(state.tickets.size)
            state.tickets.forEach { out.writeUTF(it.snapshot.ticketId.value); out.writeUTF(it.snapshot.manualCode.value) }
            out.writeInt(state.checkIns.size)
            state.checkIns.forEach { r ->
                out.writeUTF(r.id); out.writeUTF(r.eventId.value); out.writeUTF(r.ticketId.value); out.writeUTF(r.checkerId.value)
                out.writeUTF(r.checkedAt.toString()); out.writeUTF(r.attendeeName); out.writeUTF(r.displayNumber)
            }
        }
    }.toByteArray().also { require(it.size <= 8_388_608) }

    private fun DataOutputStream.cover(value: EventCover?) {
        writeBoolean(value != null)
        value?.let { writeUTF(it.assetId); writeUTF(it.mimeType); writeLong(it.byteCount) }
    }
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
}
