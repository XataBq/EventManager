package ru.eventpass.core.domain.attendance

import ru.eventpass.core.domain.registration.*

enum class AnswerResolution { VALUE, YES, NO, MISSING, OUTDATED, PARTIAL }
data class ResolvedAttendanceAnswer(val values: List<String> = emptyList(), val resolution: AnswerResolution)

/** Resolve by question type, never guess a boolean is a select value or expose opaque option IDs. */
fun resolveAttendanceAnswer(question: RegistrationQuestion?, answer: Answer?): ResolvedAttendanceAnswer {
    fun result(state: AnswerResolution, values: List<String> = emptyList()) = ResolvedAttendanceAnswer(values,state)
    if(answer == null) return result(AnswerResolution.MISSING)
    return when(question) {
        is RegistrationQuestion.Text -> if(answer is Answer.Text) {
            if(answer.value.isBlank()) result(AnswerResolution.MISSING) else result(AnswerResolution.VALUE,listOf(answer.value))
        } else result(AnswerResolution.OUTDATED)
        is RegistrationQuestion.Checkbox -> if(answer is Answer.Checkbox) result(if(answer.checked) AnswerResolution.YES else AnswerResolution.NO) else result(AnswerResolution.OUTDATED)
        is RegistrationQuestion.SingleChoice -> {
            val id=when(answer) {is Answer.SingleChoice -> answer.optionId; is Answer.Text -> answer.value; else -> null}
            if(id.isNullOrBlank()) result(if(id == null) AnswerResolution.OUTDATED else AnswerResolution.MISSING)
            else question.options.find {it.id == id}?.let {result(AnswerResolution.VALUE,listOf(it.title))} ?: result(AnswerResolution.OUTDATED)
        }
        is RegistrationQuestion.MultipleChoice -> if(answer is Answer.MultipleChoice) {
            val ids=answer.optionIds.distinct()
            val labels=ids.mapNotNull {id -> question.options.find {it.id == id}?.title}
            result(when {ids.isEmpty() -> AnswerResolution.MISSING; labels.isEmpty() -> AnswerResolution.OUTDATED; labels.size < ids.size -> AnswerResolution.PARTIAL; else -> AnswerResolution.VALUE},labels)
        } else result(AnswerResolution.OUTDATED)
        else -> result(AnswerResolution.OUTDATED)
    }
}

data class QuestionOptionCount(val id: String, val title: String?, val count: Int)
data class QuestionStatistics(val question: RegistrationQuestion, val participants: Int, val answered: Int,
    val missing: Int, val outdated: Int, val options: List<QuestionOptionCount>)
data class QuestionAnalytics(val dashboard: AttendanceDashboard, val participants: Int, val questions: List<QuestionStatistics>)
sealed interface QuestionAnalyticsResult {
    data class Ready(val analytics: QuestionAnalytics): QuestionAnalyticsResult
    data class Failed(val error: AttendanceError): QuestionAnalyticsResult
}

/** Streaming accumulation retains counts only, not participant contact details. Cancelled tickets excluded. */
class QuestionAnalyticsAccumulator(private val dashboard: AttendanceDashboard) {
    private val questions=dashboard.questions.filter {it is RegistrationQuestion.Checkbox || it is RegistrationQuestion.SingleChoice || it is RegistrationQuestion.MultipleChoice}
    private val counts=questions.associate {q -> q.id to linkedMapOf<String,Int>().apply {
        when(q) {
            is RegistrationQuestion.Checkbox -> {put("true",0);put("false",0)}
            is RegistrationQuestion.SingleChoice -> q.options.forEach {put(it.id,0)}
            is RegistrationQuestion.MultipleChoice -> q.options.forEach {put(it.id,0)}
            else -> Unit
        }
    }}
    private val answered=mutableMapOf<String,Int>();private val missing=mutableMapOf<String,Int>();private val outdated=mutableMapOf<String,Int>()
    private var participants=0
    fun add(rows: List<Attendee>) {
        rows.filter {it.status != TicketStatus.CANCELLED}.forEach {row ->
            participants++
            questions.forEach {q ->
                val answer=row.answers[q.id];val resolved=resolveAttendanceAnswer(q,answer)
                fun increment(map:MutableMap<String,Int>) {map[q.id]=(map[q.id] ?: 0)+1}
                when(resolved.resolution) {
                    AnswerResolution.MISSING -> increment(missing)
                    AnswerResolution.OUTDATED -> increment(outdated)
                    else -> {
                        increment(answered)
                        if(resolved.resolution == AnswerResolution.PARTIAL) increment(outdated)
                        val selected=when(answer) {
                            is Answer.Checkbox -> listOf(answer.checked.toString())
                            is Answer.SingleChoice -> listOf(answer.optionId)
                            is Answer.Text -> listOf(answer.value)
                            is Answer.MultipleChoice -> answer.optionIds.distinct()
                            else -> emptyList()
                        }
                        val bucket=counts.getValue(q.id)
                        selected.filter {it in bucket}.forEach {bucket[it]=bucket.getValue(it)+1}
                    }
                }
            }
        }
    }
    fun finish(): QuestionAnalytics = QuestionAnalytics(dashboard,participants,questions.map {q ->
        val titles=when(q) {
            is RegistrationQuestion.SingleChoice -> q.options.associate {it.id to it.title}
            is RegistrationQuestion.MultipleChoice -> q.options.associate {it.id to it.title}
            else -> emptyMap()
        }
        QuestionStatistics(q,participants,answered[q.id] ?: 0,missing[q.id] ?: 0,outdated[q.id] ?: 0,
            counts.getValue(q.id).map {(id,count) -> QuestionOptionCount(id,titles[id],count)})
    })
}
