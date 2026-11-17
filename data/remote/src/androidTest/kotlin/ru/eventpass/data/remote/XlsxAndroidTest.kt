package ru.eventpass.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android XML parser/ZIP implementation, including the minimum supported OS. */
@RunWith(AndroidJUnit4::class)
class XlsxAndroidTest {
    private fun workbook(sheet:String="<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>",charset:java.nio.charset.Charset=Charsets.UTF_8):ByteArray {
        val files=mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets/></workbook>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""",
            "xl/worksheets/sheet1.xml" to sheet)
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use {zip->files.forEach {(name,value)->zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray(if(name=="xl/worksheets/sheet1.xml") charset else Charsets.UTF_8));zip.closeEntry()}}
        return bytes.toByteArray()
    }
    @Test fun validWorkbookIsAcceptedByAndroidParser() {XlsxFormat.check(workbook()) {}}
    @Test fun unicodeUtf16WorkbookIsAcceptedAndUtf16DtdIsRejected() {
        XlsxFormat.check(workbook("""<?xml version="1.0" encoding="UTF-16"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>""",Charsets.UTF_16)) {}
        assertTrue(runCatching {XlsxFormat.check(workbook("""<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE worksheet [<!ENTITY unsafe "expanded">]><worksheet>&unsafe;</worksheet>""",Charsets.UTF_16)) {}}.isFailure)
    }
    @Test fun doctypeAndCorruptArchiveAreRejectedBeforeExport() {
        val entity="""<!DOCTYPE worksheet [<!ENTITY private SYSTEM "file:///data/local/tmp/private">]><worksheet>&private;</worksheet>"""
        assertTrue(runCatching {XlsxFormat.check(workbook(entity)) {}}.isFailure)
        assertTrue(runCatching {XlsxFormat.check(workbook().dropLast(20).toByteArray()) {}}.isFailure)
    }
}
