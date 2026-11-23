package ru.eventpass.core.domain.importedticket

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** Normalized PDF/OCR word bounds, measured from the top left of the page. */
data class TicketWord(val text: String, val left: Float, val right: Float, val baseline: Float, val height: Float)

object PositionedTicketRecognition {
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    private val writtenDate = Regex("(?i)(?<!\\d)(\\d{1,2})\\s+(" + months.joinToString("|") + ")\\s+(\\d{4})(?!\\d)")
    private val date = Regex("(?<!\\d)(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./]\\d{1,2}[./]\\d{4})(?!\\d)")
    private val time = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)")
    private val ignored = Regex("(?i)заказ|покупк|оформлен|purchase|order|телефон")
    private val eventKeys = setOf("событие", "мероприятие", "event", "performance")
    private val seats = mapOf(
        "сектор" to TicketField.SECTOR, "трибуна" to TicketField.SECTOR, "sector" to TicketField.SECTOR,
        "section" to TicketField.SECTOR, "block" to TicketField.SECTOR, "stand" to TicketField.SECTOR,
        "ряд" to TicketField.ROW, "row" to TicketField.ROW, "место" to TicketField.SEAT, "seat" to TicketField.SEAT
    )

    /** Each lookup uses a label's position or the observed order of a row, independently of the provider. */
    fun extract(words: List<TicketWord>): Map<TicketField, TicketFieldCandidate> {
        val rows = rows(words.take(8000))
        val result = mutableMapOf<TicketField, TicketFieldCandidate>()
        fun add(key: TicketField, value: String, source: String) {
            if (value.isBlank()) return
            val previous = result[key]
            if (previous == null) result[key] = TicketFieldCandidate(value.take(300), FieldEvidence.LAYOUT, source.take(500))
            else if (previous.value != value && previous.evidence != FieldEvidence.MULTIPLE_VALUES)
                result[key] = TicketFieldCandidate("", FieldEvidence.MULTIPLE_VALUES, (previous.sourceLine + " / " + source).take(500))
        }

        fun addEventTime(text: String) {
            if (ignored.containsMatchIn(text)) return
            dateValue(text)?.let { add(TicketField.DATE, it, text) }
            time.findAll(text).map { it.value.padStart(5, '0') }.distinct().singleOrNull()
                ?.let { add(TicketField.TIME, it, text) }
        }

        rows.forEachIndexed { index, row ->
            for (anchor in row.filter { clean(it.text) in eventKeys }) {
                // An isolated header can precede a multiline title and its adjacent date.
                if (row.any { it !== anchor && abs(it.left - anchor.left) < 0.32f }) continue
                val panel = rows.drop(index + 1).takeWhile { line -> line.first().baseline - anchor.baseline <= 0.23f }
                    .map { line -> line.filter { it.left >= anchor.left - 0.02f && it.left < anchor.left + 0.48f } }
                    .filter { it.isNotEmpty() }
                val eventDate = panel.indexOfFirst { dateValue(it.text()) != null && !ignored.containsMatchIn(it.text()) }
                if (eventDate in 1..4) {
                    val title = panel.take(eventDate).joinToString(" ") { it.text() }.trim()
                    if (title.any(Char::isLetter)) {
                        val nearbyDate = panel[eventDate].text()
                        add(TicketField.TITLE, title, anchor.text + " / " + title)
                        addEventTime(nearbyDate)
                    }
                }
            }
            if (ignored.containsMatchIn(row.text())) return@forEachIndexed
            // Some forms put a match name before date and time on the same line, with no field labels.
            val line = row.text()
            val numericDate = date.find(line)
            if (numericDate != null && time.containsMatchIn(line.substring(numericDate.range.last + 1))) {
                val before = line.substring(0, numericDate.range.first).trim()
                if (before.length in 7..120 && Regex("\\p{L}\\s+[-–—]\\s+\\p{L}").containsMatchIn(before)
                    && before.none(Char::isDigit)) {
                    add(TicketField.TITLE, before, line)
                    addEventTime(line.substring(numericDate.range.first))
                }
            }
        }

        rows.forEachIndexed { index, row ->
            val anchors = row.mapNotNull { word -> seats[clean(word.text)]?.let { it to word } }
                .distinctBy { it.first }
            if (anchors.size >= 2) {
                val start = anchors.first().second.left
                val finish = anchors.last().second.right + 0.07f
                val next = rows.drop(index + 1).takeWhile { it.first().baseline - row.first().baseline <= 0.045f }
                    .map { line -> line.filter { it.left in (start - 0.02f)..finish } }
                    .firstOrNull { it.any { word -> word.text.any(Char::isDigit) || word.text.length == 1 } }
                    ?: return@forEachIndexed
                anchors.forEachIndexed { column, (key, header) ->
                    val boundary = anchors.getOrNull(column + 1)?.second?.left ?: finish
                    val value = next.filter { it.left >= header.left - 0.013f && it.left < boundary - 0.005f }.text()
                    if (seatValue(key, value)) add(key, value, header.text + " / " + value)
                }
                val before = rows.take(index).takeLast(5).map { line ->
                    line.filter { it.left >= start - 0.02f && it.left <= start + 0.46f }.text()
                }.filter(String::isNotBlank)
                val address = before.lastOrNull()
                val venue = before.getOrNull(before.lastIndex - 1)
                if (address != null && Regex("(?i)пр-д|проспект|улица|ул\\.|шоссе|наб\\.|пер\\.|street|avenue").containsMatchIn(address)) {
                    add(TicketField.ADDRESS, address, address)
                    if (!venue.isNullOrBlank() && venue.any(Char::isLetter) && dateValue(venue) == null)
                        add(TicketField.VENUE, venue, venue)
                }
            } else {
                val (field, anchor) = anchors.singleOrNull() ?: return@forEachIndexed
                val before = row.lastOrNull { it.right <= anchor.left && anchor.left - it.right <= 0.16f }
                val after = row.firstOrNull { it.left >= anchor.right && it.left - anchor.right <= 0.16f }
                val values = listOfNotNull(before, after).map { it.text.trim(':', '.', ',', ' ') }.filter { seatValue(field, it) }.distinct()
                values.singleOrNull()?.let { add(field, it, row.text()) }
            }
        }
        return result
    }

    private fun rows(words: List<TicketWord>): List<List<TicketWord>> {
        val lines = mutableListOf<MutableList<TicketWord>>()
        for (word in words.filter { it.text.isNotBlank() }.sortedBy { it.baseline }) {
            val line = lines.lastOrNull()
            if (line != null && abs(word.baseline - line.first().baseline) <= 0.006f) line.add(word)
            else lines += mutableListOf(word)
        }
        return lines.map { line -> line.sortedBy { it.left }.distinctBy { Triple(it.text, (it.left * 1000).toInt(), (it.right * 1000).toInt()) } }
    }

    private fun List<TicketWord>.text() = joinToString(" ") { it.text }.trim()
    private fun clean(value: String) = value.trim(':', '.', ',', ' ').lowercase(Locale.ROOT)
    private fun seatValue(field: TicketField, value: String): Boolean = when (field) {
        TicketField.SECTOR -> value.length in 1..45 && (value.any(Char::isDigit) || value.length <= 3 ||
            Regex("(?i)^(трибуна|сектор|блок|stand|block)\\s+").containsMatchIn(value))
        else -> Regex("(?iu)^(?:\\d{1,5}[\\p{L}]?|[A-ZА-Я])$").matches(value)
    }

    private fun dateValue(text: String): String? {
        val values = mutableSetOf<String>()
        date.findAll(text).forEach { match ->
            runCatching {
                if ('-' in match.value) LocalDate.parse(match.value)
                else match.value.split('.', '/').map(String::toInt).let { LocalDate.of(it[2], it[1], it[0]) }
            }.getOrNull()?.let { values += it.toString() }
        }
        writtenDate.findAll(text).forEach { match ->
            val (day, month, year) = match.destructured
            runCatching { LocalDate.of(year.toInt(), months.indexOf(month.lowercase(Locale.ROOT)) + 1, day.toInt()) }
                .getOrNull()?.let { values += it.toString() }
        }
        return values.singleOrNull()
    }
}
