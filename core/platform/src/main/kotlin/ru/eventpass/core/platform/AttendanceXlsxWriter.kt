package ru.eventpass.core.platform

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.eventpass.core.domain.documents.DocumentSnapshot
import ru.eventpass.core.domain.registration.*

/** Minimal streaming SpreadsheetML. User input is always inlineStr, never a formula. */
object AttendanceXlsxWriter {
    fun write(data: DocumentSnapshot.Attendance, output: OutputStream, check: () -> Unit = {}) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Участники" sheetId="1" r:id="rId1"/><sheet name="Снимок" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf fontId="1" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            fun sheet(name: String, rows: Sequence<List<Any?>>) {
                zip.putNextEntry(ZipEntry(name)); val writer = zip.writer(Charsets.UTF_8)
                writer.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="16384" width="26" customWidth="1"/></cols><sheetData>""")
                rows.forEachIndexed { index, cells ->
                    check(); writer.write("<row r=\"${index + 1}\">")
                    cells.forEachIndexed { column, value ->
                        check(); val ref = "${columnName(column)}${index + 1}"; val style = if (index == 0) 1 else 0
                        if (value is Boolean) writer.write("<c r=\"$ref\" s=\"$style\" t=\"b\"><v>${if(value) 1 else 0}</v></c>")
                        else {
                            val text = value?.toString().orEmpty(); require(text.length <= 32767) { "Cell exceeds SpreadsheetML limit" }
                            writer.write("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(text)}</t></is></c>")
                        }
                    }
                    writer.write("</row>")
                }
                writer.write("</sheetData></worksheet>"); writer.flush(); zip.closeEntry()
            }
            val questions = data.event.questions.associateBy { it.id }
            val ids = (questions.keys + data.rows.flatMap { it.registration.answers.keys }).distinct()
            val fixed = listOf("registrationId", "displayNumber", "Имя", "Email", "Телефон", "Регистрация (consentAcceptedAt)", "Статус", "Первый проход", "checkerId", "formVersion")
            sheet("xl/worksheets/sheet1.xml", sequence {
                yield(fixed + ids.flatMap { listOf("$it / ${questions[it]?.title ?: "Архивный вопрос"}", "$it / optionId") })
                data.rows.forEach { row ->
                    val r = row.registration
                    yield(listOf(r.id, row.displayNumber, r.attendeeName, r.attendeeEmail, r.phone, r.consentAcceptedAt, row.status.name,
                        row.checkedAt, row.checkerId?.value, r.formVersion) + ids.flatMap { id ->
                        when(val answer = r.answers[id]) {
                            is Answer.Text -> listOf(answer.value, null)
                            is Answer.Checkbox -> listOf(answer.checked, null)
                            is Answer.SingleChoice -> listOf((questions[id] as? RegistrationQuestion.SingleChoice)?.options?.find { it.id == answer.optionId }?.title, answer.optionId)
                            null -> listOf(null, null)
                        }
                    })
                }
            })
            sheet("xl/worksheets/sheet2.xml", sequenceOf(listOf("Поле", "Значение"), listOf("eventId", data.event.id.value),
                listOf("Название", data.event.title), listOf("Начало", data.event.startsAt.toString()), listOf("Зона", data.event.zoneId),
                listOf("Место", data.event.venue), listOf("SnapshotAt (UTC)", data.snapshotAt.toString()),
                listOf("Регистраций", data.rows.size.toString()), listOf("Время регистрации", "Demo: существующий consentAcceptedAt; время прохода пустое без first record")))
        }
    }
    private fun columnName(index: Int): String { var n=index+1; var result=""; while(n>0) { n--; result=('A'+n%26)+result; n/=26 }; return result }
    private fun escape(value: String) = buildString {
        value.codePoints().forEach { c -> when(c) {
            38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;")
            else -> if (c == 9 || c == 10 || c == 13 || c in 32..0xD7FF || c in 0xE000..0xFFFD || c in 0x10000..0x10FFFF) appendCodePoint(c) else append('\uFFFD')
        } }
    }
}
