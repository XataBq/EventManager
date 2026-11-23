package ru.eventpass.app.feature.pdfvault

import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.importedticket.FieldEvidence
import ru.eventpass.core.domain.importedticket.TicketField
import java.io.File
import java.util.UUID

class PdfVaultIntegrationTest {
    @Test fun twoPagesKeepTheirOwnSeatsAndCodesAndCanBeOpenedAgain() = runBlocking {
        val (context, source) = fixture("01-concert-two-pages.pdf")
        val owner = UUID.randomUUID().toString()
        val vault = PdfVaultStorage(context, owner)
        val start = SystemClock.elapsedRealtime()
        val pages = vault.read(Uri.fromFile(source))
        println("AIP004 Cyrillic PDF two pages processed in " + (SystemClock.elapsedRealtime()-start) + " ms")
        val cards = mutableListOf<ImportedTicket>()
        try {
            assertEquals(2, pages.size)
            for ((index, page) in pages.withIndex()) {
                val fields = page.candidates.mapValues { it.value.value }.filterValues(String::isNotBlank)
                assertEquals("Северное сияние", fields[TicketField.TITLE])
                assertEquals("2026-12-20", fields[TicketField.DATE])
                assertEquals("19:30", fields[TicketField.TIME])
                assertEquals("Большой концертный зал", fields[TicketField.VENUE])
                assertEquals("7", fields[TicketField.ROW])
                assertEquals("3", fields[TicketField.ENTRANCE])
                assertEquals("Тестовый Гость", fields[TicketField.HOLDER])
                assertEquals("1500 RUB", fields[TicketField.PRICE])
                assertEquals("Студенческий", page.extras["Тариф"])
                val seat = (index + 15).toString()
                assertEquals(seat, fields[TicketField.SEAT])
                val code = page.codes.single()
                assertEquals("AIP004-DUMMY-QR-SEAT-" + seat, code.value)
                assertCodeRoundTrip(code)
                cards += vault.save(page, fields, code)
            }
            val reopened = PdfVaultStorage(context, owner)
            assertEquals(cards.toSet(), reopened.list().toSet())
            assertEquals(1, cards.map(::groupKey).distinct().size)
            assertTrue(PdfVaultStorage(context, "other-" + owner).list().isEmpty())
            try {
                reopened.read(Uri.fromFile(source))
                fail("Expected duplicate detection")
            } catch (failure: PdfImportFailure) {
                assertEquals(PdfProblem.DUPLICATE, failure.problem)
            }
            reopened.delete(cards.first())
            val (bitmap, pageCount) = reopened.page(cards.last(), cards.last().page)
            bitmap.recycle()
            assertEquals(2, pageCount)
        } finally {
            cards.forEach(vault::delete)
            pages.distinctBy { it.file }.forEach { it.file.delete() }
            source.delete()
        }
    }

    @Test fun linearBarcodeAndImageOnlyAndAmbiguousPdfUseDifferentPaths() = runBlocking {
        val (context, linearSource) = fixture("02-theatre-code128.pdf")
        val (_, scanSource) = fixture("03-scan-only.pdf")
        val (_, conflictSource) = fixture("04-two-codes.pdf")
        val vault = PdfVaultStorage(context, UUID.randomUUID().toString())
        val sources = listOf(linearSource, scanSource, conflictSource)
        val drafts = mutableListOf<PdfDraft>()
        try {
            for (file in sources) drafts += vault.read(Uri.fromFile(file))
            val (linear, scan, conflict) = drafts
            assertEquals("Winter Theatre", linear.candidates[TicketField.TITLE]?.value)
            assertEquals("20:15", linear.candidates[TicketField.TIME]?.value)
            val code = linear.codes.single()
            assertEquals("CODE_128", code.format)
            assertEquals("AIP004-CODE128-002", code.value)
            assertCodeRoundTrip(code)
            assertFalse(scan.hasText)
            assertNotNull(scan.candidates[TicketField.TITLE])
            assertEquals("AIP004-IMAGE-ONLY", scan.codes.single().value)
            assertEquals(setOf("AIP004-CHOICE-ONE", "AIP004-CHOICE-TWO"), conflict.codes.map { it.value }.toSet())
            assertEquals(FieldEvidence.MULTIPLE_VALUES, conflict.candidates[TicketField.DATE]?.evidence)
            assertEquals("", conflict.candidates[TicketField.DATE]?.value)
        } finally {
            drafts.distinctBy { it.file }.forEach { it.file.delete() }
            sources.forEach(File::delete)
        }
    }

    @Test fun privatePdfSamples() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("privateAipSamples") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = PdfVaultStorage(context, UUID.randomUUID().toString())
        val problems = mutableListOf<String>()
        for ((name, count) in listOf("cska" to 3, "racing" to 6)) {
            val source = File(context.cacheDir, "aip-acceptance-" + name + ".pdf")
            val start = SystemClock.elapsedRealtime()
            val pages = vault.read(Uri.fromFile(source)) { progress ->
                println("AIP004 " + name + " stage=" + progress.stage + " page=" + progress.page +
                    " atMs=" + (SystemClock.elapsedRealtime() - start))
            }
            try {
                assertEquals(count, pages.size)
                for (page in pages) {
                    val fields = page.candidates.filterValues { it.value.isNotBlank() }.keys
                    val formats = page.codes.map { it.format }.distinct()
                    println("AIP004 " + name + " page=" + (page.page + 1) + " fields=" + fields + " formats=" + formats)
                    if (TicketField.TITLE !in fields || TicketField.DATE !in fields || TicketField.TIME !in fields)
                        problems += name + " page " + (page.page + 1) + " missing title, date or time"
                    if ((name == "cska" && "EAN_13" !in formats) || (name == "racing" && "CODE_128" !in formats))
                        problems += name + " page " + (page.page + 1) + " missing expected barcode"
                    if (name == "cska" && !fields.containsAll(listOf(TicketField.SECTOR, TicketField.ROW, TicketField.SEAT)))
                        problems += name + " page " + (page.page + 1) + " missing seat details"
                }
                if (name == "racing") {
                    val fourth = pages[3]
                    val rechecked = vault.recheck(fourth) {}
                    assertEquals(fourth.file, rechecked.file)
                    assertEquals(fourth.page, rechecked.page)
                    assertTrue(rechecked.codes.any { it.format == "CODE_128" })
                    assertTrue(vault.list().isEmpty())
                }
                val sample = pages.first()
                val editable = vault.save(sample, mapOf(TicketField.TITLE to "Temporary test entry"), null)
                try {
                    val replacement = vault.reread(editable)
                    assertEquals(editable, replacement.replaces)
                    val changed = vault.save(replacement, mapOf(TicketField.TITLE to "Updated test entry"), null)
                    assertEquals(editable.id, changed.id)
                    assertEquals(listOf(changed), vault.list())
                } finally { vault.delete(editable) }
                println("AIP004 " + name + " durationMs=" + (SystemClock.elapsedRealtime() - start))
            } finally { pages.distinctBy { it.file }.forEach { it.file.delete() } }
        }
        assertTrue(problems.joinToString(" / "), problems.isEmpty())
    }

    private fun assertCodeRoundTrip(code: ImportedCode) {
        val bitmap = requireNotNull(codeBitmap(code))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
            val decoded = MultiFormatReader().decode(image)
            assertEquals(code.format, decoded.barcodeFormat.name)
            assertEquals(code.value, decoded.text)
        } finally { bitmap.recycle() }
    }

    private fun fixture(name: String): Pair<android.content.Context, File> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val target = File.createTempFile("aip004-test-", ".pdf", context.cacheDir)
        instrumentation.context.assets.open("aip004/" + name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return context to target
    }
}
