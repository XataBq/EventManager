package ru.eventpass.core.domain.registration

data class ValidatedRegistration(
    val name: String,
    val email: String,
    val phone: String?,
    val answers: Map<String, Answer>,
)

sealed interface RegistrationValidation {
    data class Valid(val value: ValidatedRegistration) : RegistrationValidation
    data class Invalid(val errors: List<FieldError>) : RegistrationValidation
}

object RegistrationValidator {
    fun validate(
        command: GuestRegistrationCommand,
        questions: List<RegistrationQuestion>,
        policy: RegistrationPolicy,
    ): RegistrationValidation {
        val errors = mutableListOf<FieldError>()
        val name = command.name.trim()
        val email = command.email.trim()
        if (name.isEmpty()) errors += FieldError(RegistrationField.Name, FieldIssue.REQUIRED)
        else if (name.any { it.isISOControl() }) {
            errors += FieldError(RegistrationField.Name, FieldIssue.INVALID_NAME)
        }
        // Basic form validation only. No provider-specific rewriting or script restrictions.
        val emailParts = email.split('@')
        if (emailParts.size != 2 || emailParts.any { it.isBlank() } ||
            email.any { it.isWhitespace() || it.isISOControl() }
        ) errors += FieldError(RegistrationField.Email, FieldIssue.INVALID_EMAIL)

        val rawPhone = command.phone?.trim().orEmpty()
        val phone = if (policy.phone == FieldRequirement.HIDDEN || rawPhone.isEmpty()) null
        else normalizeRussianPhone(rawPhone)
        if (policy.phone == FieldRequirement.REQUIRED && rawPhone.isEmpty()) {
            errors += FieldError(RegistrationField.Phone, FieldIssue.REQUIRED)
        } else if (policy.phone != FieldRequirement.HIDDEN && rawPhone.isNotEmpty() && phone == null) {
            errors += FieldError(RegistrationField.Phone, FieldIssue.INVALID_PHONE)
        }
        if (!command.consentAccepted) errors += FieldError(RegistrationField.Consent, FieldIssue.REQUIRED)

        command.answers.keys.filter { answerId -> questions.none { it.id == answerId } }.forEach {
            errors += FieldError(RegistrationField.Question(it), FieldIssue.UNKNOWN_QUESTION)
        }
        questions.forEach { question ->
            val answer = command.answers[question.id]
            val issue = when (question) {
                is RegistrationQuestion.Text -> when {
                    answer == null -> if (question.required) FieldIssue.REQUIRED else null
                    answer !is Answer.Text -> FieldIssue.INVALID_ANSWER
                    question.required && answer.value.isBlank() -> FieldIssue.REQUIRED
                    else -> null
                }
                is RegistrationQuestion.Checkbox -> when {
                    answer == null -> if (question.required) FieldIssue.REQUIRED else null
                    answer !is Answer.Checkbox -> FieldIssue.INVALID_ANSWER
                    question.required && !answer.checked -> FieldIssue.REQUIRED
                    else -> null
                }
                is RegistrationQuestion.SingleChoice -> when {
                    answer == null -> if (question.required) FieldIssue.REQUIRED else null
                    answer !is Answer.SingleChoice -> FieldIssue.INVALID_ANSWER
                    question.options.none { it.id == answer.optionId } -> FieldIssue.INVALID_ANSWER
                    else -> null
                }
            }
            if (issue != null) errors += FieldError(RegistrationField.Question(question.id), issue)
        }
        return if (errors.isNotEmpty()) RegistrationValidation.Invalid(errors.toList())
        else RegistrationValidation.Valid(
            ValidatedRegistration(name, email, phone, command.answers.mapValues { (_, answer) ->
                if (answer is Answer.Text) answer.copy(value = answer.value.trim()) else answer
            }),
        )
    }

    private fun normalizeRussianPhone(raw: String): String? {
        if (raw.any { it !in "0123456789+()- " }) return null
        val digits = raw.filter { it in '0'..'9' }
        if (digits.length != 11) return null
        return when {
            raw.startsWith("+7") && raw.count { it == '+' } == 1 -> "+$digits"
            raw.startsWith('8') && '+' !in raw -> "+7${digits.drop(1)}"
            else -> null
        }
    }
}
