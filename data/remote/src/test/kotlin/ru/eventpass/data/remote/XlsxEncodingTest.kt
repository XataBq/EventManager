package ru.eventpass.data.remote

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class XlsxEncodingTest {
    private fun workbook(sheet:ByteArray):ByteArray {
        val parts=mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets/></workbook>""",
            "_rels/.rels" to "<Relationships/>","xl/_rels/workbook.xml.rels" to "<Relationships/>")
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use {zip->
            parts.forEach {(name,body)->zip.putNextEntry(ZipEntry(name));zip.write(body.toByteArray());zip.closeEntry()}
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"));zip.write(sheet);zip.closeEntry()
        }
        return output.toByteArray()
    }
    @Test fun supportedUnicodeEncodingIsAcceptedButDtdCannotChangeEncodingToEscapeGuard() {
        val namespace="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        XlsxFormat.check(workbook("""<?xml version="1.0" encoding="UTF-16"?><worksheet xmlns="$namespace"><sheetData/></worksheet>""".toByteArray(Charsets.UTF_16))) {}
        for(encoding in listOf("UTF-8","UTF-16","Cp037")) {
            val xml="""<?xml version="1.0" encoding="$encoding"?><!DOCTYPE worksheet [<!ENTITY unsafe "internal expansion">]><worksheet xmlns="$namespace">&unsafe;</worksheet>"""
            assertTrue("DTD accepted for $encoding",runCatching {XlsxFormat.check(workbook(xml.toByteArray(Charset.forName(encoding)))) {}}.isFailure)
        }
        val mixed="""<?xml version="1.0" encoding="Cp037"?>""".toByteArray()+
            """<!DOCTYPE worksheet [<!ENTITY unsafe "expansion">]><worksheet>&unsafe;</worksheet>""".toByteArray(Charset.forName("Cp037"))
        assertTrue(runCatching {XlsxFormat.check(workbook(mixed)) {}}.isFailure)
    }
}
