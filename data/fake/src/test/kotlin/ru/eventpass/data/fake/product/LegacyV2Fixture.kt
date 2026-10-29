package ru.eventpass.data.fake.product

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.*

/** Frozen schema-2 writer from dbbbea1, independent input for migration tests. Do not upgrade it. */
internal object LegacyV2Fixture {
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

}
