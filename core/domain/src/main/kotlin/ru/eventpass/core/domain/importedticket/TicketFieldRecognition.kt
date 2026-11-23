package ru.eventpass.core.domain.importedticket

import java.time.LocalDate

enum class TicketField {
    TITLE, DATE, TIME, VENUE, ADDRESS, SECTOR, ROW, SEAT, HOLDER, ENTRANCE, TICKET_NUMBER, PRICE
}

enum class FieldEvidence { LABEL, LAYOUT, OCR, UNIQUE_VALUE, MULTIPLE_VALUES }

data class TicketFieldCandidate(val value: String, val evidence: FieldEvidence, val sourceLine: String)

object TicketFieldRecognition {
    private val labels = linkedMapOf(
        TicketField.TITLE to "мероприятие|событие|название мероприятия|event(?: name)?|performance",
        TicketField.DATE to "дата мероприятия|дата начала|дата|event date|date",
        TicketField.TIME to "время(?: начала)?|начало|start(?: time)?|time",
        TicketField.VENUE to "площадка|место проведения|арена|театр|venue|location",
        TicketField.ADDRESS to "адрес(?: площадки)?|address",
        TicketField.SECTOR to "сектор|трибуна|зона|секция|section|sector|block|stand",
        TicketField.ROW to "ряд|row",
        TicketField.SEAT to "место|seat",
        TicketField.HOLDER to "фио|владелец билета|посетитель|ticket holder|holder|guest name",
        TicketField.ENTRANCE to "вход|entrance|gate",
        TicketField.TICKET_NUMBER to "номер билета|билет №|ticket number|ticket no\\.?",
        TicketField.PRICE to "цена|стоимость|price"
    )
    private val index = labels.flatMap { (field, alternatives) -> alternatives.split('|').map { field to it } }
        .sortedByDescending { it.second.length }
    private val label = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(" + index.joinToString("|") { it.second } + ")\\s*[:：]\\s*"
    )
    private val fieldLabels = index.map { (field, pattern) -> field to Regex(pattern, RegexOption.IGNORE_CASE) }
    private val datePattern = Regex("(?<!\\d)(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./]\\d{1,2}[./]\\d{4})(?!\\d)")
    private val timePattern = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)")
    private val orderLine = Regex("(?i)заказ|покупк|оформлен|purchase|order|issued")
    private val spaces = Regex("[\\p{Zs}\\t]+")
    private val extraLabel = Regex("(?i)^(тариф|тип билета|возрастное ограничение|уровень|подъезд|дверь|ticket type|fare|level|door)\\s*[:：]\\s*(.{1,300})$")

    fun extractExtras(text: String): Map<String, String> = text.take(100_000).lineSequence()
        .mapNotNull { extraLabel.matchEntire(it.trim()) }
        .groupBy { it.groupValues[1].lowercase(java.util.Locale.ROOT) }
        .values.mapNotNull { matches ->
            val values = matches.map { it.groupValues[2].trim() }.filter(String::isNotBlank).distinct()
            values.singleOrNull()?.let { matches.first().groupValues[1] to it }
        }.take(8).toMap()

    fun extract(text: String): Map<TicketField, TicketFieldCandidate> {
        val lines = text.take(100_000).lineSequence().map { it.replace(spaces, " ").trim() }
            .filter(String::isNotBlank).take(800).toList()
        val values = mutableMapOf<TicketField, MutableList<Pair<String, String>>>()
        lines.forEachIndexed { lineIndex, line ->
            val matches = label.findAll(line).toList()
            matches.forEachIndexed matches@ { index, match ->
                val field = fieldLabels.firstOrNull { it.second.matches(match.groupValues[1]) }?.first ?: return@matches
                val remainder = line.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: line.length)
                val nextLine = lines.getOrNull(lineIndex + 1)?.takeUnless { label.containsMatchIn(it) }
                val raw = remainder.trim(' ', ';', '|', '·').ifBlank { if (matches.size == 1) nextLine.orEmpty() else "" }
                val value = when (field) {
                    TicketField.DATE -> datePattern.findAll(raw).mapNotNull { normalizeDate(it.value) }.distinct().singleOrNull()
                    TicketField.TIME -> timePattern.findAll(raw).map { it.value.padStart(5, '0') }.distinct().singleOrNull()
                    else -> raw.take(300)
                }
                if (!value.isNullOrBlank()) {
                    val source = if (remainder.isBlank()) (line + " " + raw).take(500) else line.take(500)
                    values.getOrPut(field, ::mutableListOf).add(value to source)
                }
            }
        }
        fun candidate(items: List<Pair<String, String>>, evidence: FieldEvidence): TicketFieldCandidate? {
            val distinct = items.distinctBy { it.first }
            return when (distinct.size) {
                0 -> null
                1 -> TicketFieldCandidate(distinct.single().first, evidence, distinct.single().second)
                else -> TicketFieldCandidate("", FieldEvidence.MULTIPLE_VALUES, distinct.take(3).joinToString(" / ") { it.second })
            }
        }
        val result = values.mapNotNull { (field, candidates) -> candidate(candidates, FieldEvidence.LABEL)?.let { field to it } }
            .toMap().toMutableMap()
        fun suggest(field: TicketField, pattern: Regex, normalize: (String) -> String?) {
            if (field in result) return
            val candidates = lines.filterNot(orderLine::containsMatchIn).flatMap { line ->
                pattern.findAll(line).mapNotNull { normalize(it.value)?.let { value -> value to line.take(500) } }.toList()
            }
            candidate(candidates, FieldEvidence.UNIQUE_VALUE)?.let { result[field] = it }
        }
        suggest(TicketField.DATE, datePattern, ::normalizeDate)
        suggest(TicketField.TIME, timePattern) { it.padStart(5, '0') }
        return result
    }

    private fun normalizeDate(value: String): String? = runCatching {
        if ('-' in value) LocalDate.parse(value)
        else {
            val parts = value.split('.', '/').map(String::toInt)
            LocalDate.of(parts[2], parts[1], parts[0])
        }
    }.getOrNull()?.toString()
}
