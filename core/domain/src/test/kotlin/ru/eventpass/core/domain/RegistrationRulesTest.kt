package ru.eventpass.core.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.AvailablePlaces
import ru.eventpass.core.domain.event.Event
import ru.eventpass.core.domain.event.EventAccessPolicy
import ru.eventpass.core.domain.event.EventId
import ru.eventpass.core.domain.event.EventLifecycle
import ru.eventpass.core.domain.event.RegistrationAvailability
import ru.eventpass.core.domain.event.RegistrationClosureReason
import ru.eventpass.core.domain.event.ScanGrant
import ru.eventpass.core.domain.event.UserId
import ru.eventpass.core.domain.event.registrationAvailability
import ru.eventpass.core.domain.registration.Answer
import ru.eventpass.core.domain.registration.FieldIssue
import ru.eventpass.core.domain.registration.FieldRequirement
import ru.eventpass.core.domain.registration.GuestRegistrationCommand
import ru.eventpass.core.domain.registration.RegistrationField
import ru.eventpass.core.domain.registration.RegistrationPolicy
import ru.eventpass.core.domain.registration.RegistrationQuestion
import ru.eventpass.core.domain.registration.RegistrationValidation
import ru.eventpass.core.domain.registration.RegistrationValidator

class RegistrationRulesTest {
    private val now = Instant.parse("2026-10-27T09:00:00Z")
    private val owner = UserId("owner")
    private val guest = UserId("guest")
    private val event = Event(
        EventId("event"), owner, "Event", now.plusSeconds(60), now.plusSeconds(120),
        "Europe/Moscow", "Hall", 10, EventLifecycle.PUBLISHED, true, 1, emptyList(),
    )
    private fun command(name: String = "王小明", phone: String? = null) = GuestRegistrationCommand(
        event.id, 1, name, "new@example.com", phone, consentAccepted = true,
    )

    @Test fun unlimitedAndNoPlacesAreDifferent() {
        assertEquals(
            RegistrationAvailability.Open(AvailablePlaces.Unlimited),
            event.copy(capacity = null).registrationAvailability(now, 500),
        )
        assertEquals(
            RegistrationAvailability.Closed(RegistrationClosureReason.FULL),
            event.copy(capacity = 0).registrationAvailability(now, 0),
        )
        assertEquals(
            RegistrationAvailability.Open(AvailablePlaces.Limited(1)),
            event.registrationAvailability(now, 9),
        )
    }

    @Test fun startClosesRegistrationWithoutFinishingTheEvent() {
        assertEquals(
            RegistrationAvailability.Closed(RegistrationClosureReason.STARTED),
            event.registrationAvailability(event.startsAt, 0),
        )
        assertEquals(
            RegistrationAvailability.Closed(RegistrationClosureReason.FINISHED),
            event.registrationAvailability(event.endsAt!!, 0),
        )
        assertEquals(
            RegistrationAvailability.Closed(RegistrationClosureReason.FINISHED),
            event.copy(lifecycle = EventLifecycle.FINISHED).registrationAvailability(now, 0),
        )
        assertEquals(
            RegistrationAvailability.Closed(RegistrationClosureReason.CLOSED),
            event.copy(registrationOpen = false).registrationAvailability(now, 0),
        )
    }

    @Test fun grantsAreScopedToBothUserAndEventAndDoNotRemoveParticipation() {
        val availability = event.registrationAvailability(now, 1)
        val account = Account(guest, "guest@example.com", true)
        val unrelated = listOf(ScanGrant(EventId("other"), guest), ScanGrant(event.id, owner))
        val participant = EventAccessPolicy.resolve(event, account, true, unrelated, availability)
        assertTrue(participant.canViewOwnTicket)
        assertFalse(participant.canScan)
        val volunteer = EventAccessPolicy.resolve(
            event, account, true, unrelated + ScanGrant(event.id, guest), availability,
        )
        assertTrue(volunteer.canScan)
        assertFalse(volunteer.canManage)
        assertFalse(volunteer.canViewAttendees)
        assertFalse(volunteer.canExport)
        val revoked = EventAccessPolicy.resolve(
            event, account, true, listOf(ScanGrant(event.id, guest, active = false)), availability,
        )
        assertFalse(revoked.canScan)
        assertTrue(revoked.canViewOwnTicket)
        val owned = EventAccessPolicy.resolve(event, account.copy(id = owner), false, emptyList(), availability)
        assertTrue(owned.canScan && owned.canManage && owned.canExport && owned.canRegister)
    }

    @Test fun namesHaveNoScriptOrWordCountRestrictionAndPhoneIsOptional() {
        listOf("王小明", "محمد", "李", "Anne-Marie O’Neill", "Иван").forEach { name ->
            val result = RegistrationValidator.validate(command(name), emptyList(), RegistrationPolicy())
            assertTrue(name, result is RegistrationValidation.Valid)
        }
        val invalid = RegistrationValidator.validate(command("A\nB"), emptyList(), RegistrationPolicy())
        assertTrue((invalid as RegistrationValidation.Invalid).errors.any {
            it.field == RegistrationField.Name && it.issue == FieldIssue.INVALID_NAME
        })
    }

    @Test fun phoneRequirementCanChangeInBothDirectionsAndHiddenDoesNotSubmitIt() {
        fun validate(policy: FieldRequirement, phone: String? = null) = RegistrationValidator.validate(
            command(phone = phone), emptyList(), RegistrationPolicy(policy),
        )
        assertTrue(validate(FieldRequirement.REQUIRED) is RegistrationValidation.Invalid)
        assertTrue(validate(FieldRequirement.OPTIONAL) is RegistrationValidation.Valid)
        val normalized = validate(FieldRequirement.REQUIRED, "8 (999) 123-45-67") as RegistrationValidation.Valid
        assertEquals("+79991234567", normalized.value.phone)
        assertTrue(validate(FieldRequirement.OPTIONAL, "+1 555 1234567") is RegistrationValidation.Invalid)
        val hidden = validate(FieldRequirement.HIDDEN, "ignored") as RegistrationValidation.Valid
        assertEquals(null, hidden.value.phone)
        assertTrue(validate(FieldRequirement.REQUIRED) is RegistrationValidation.Invalid)
    }

    @Test fun dynamicQuestionsValidateRequiredTypeOptionAndUnknownIds() {
        val questions = listOf(
            RegistrationQuestion.Text("text", "Text", true),
            RegistrationQuestion.Checkbox("check", "Check", true),
            RegistrationQuestion.SingleChoice("choice", "Choice", true, listOf(RegistrationQuestion.Option("a", "A"))),
        )
        val missing = RegistrationValidator.validate(command(), questions, RegistrationPolicy())
        assertEquals(3, (missing as RegistrationValidation.Invalid).errors.size)
        val wrong = command().copy(answers = mapOf(
            "text" to Answer.Checkbox(true), "check" to Answer.Checkbox(false),
            "choice" to Answer.SingleChoice("missing"), "unknown" to Answer.Text("value"),
        ))
        val invalid = RegistrationValidator.validate(wrong, questions, RegistrationPolicy()) as RegistrationValidation.Invalid
        assertEquals(4, invalid.errors.size)
        val valid = command().copy(answers = mapOf(
            "text" to Answer.Text(" answer "), "check" to Answer.Checkbox(true), "choice" to Answer.SingleChoice("a"),
        ))
        val accepted = RegistrationValidator.validate(valid, questions, RegistrationPolicy()) as RegistrationValidation.Valid
        assertEquals(Answer.Text("answer"), accepted.value.answers["text"])
    }
}
