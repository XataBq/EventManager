package ru.eventpass.app.feature.pdfvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.eventpass.core.domain.importedticket.TicketField
import ru.eventpass.core.domain.importedticket.TicketWord
import ru.eventpass.core.domain.importedticket.FieldEvidence
import ru.eventpass.core.domain.importedticket.PositionedTicketRecognition
import ru.eventpass.core.domain.importedticket.TicketFieldCandidate
import ru.eventpass.core.domain.importedticket.TicketFieldRecognition
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val FILE_LIMIT = 12L * 1024 * 1024
private const val VAULT_LIMIT = 100L * 1024 * 1024
private const val MAX_PAGES = 8
private const val MAX_CARDS = 200
private val fingerprint = Regex("[0-9a-f]{64}")
private val linearFormats = listOf(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.EAN_13,
    BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.ITF)
private val formats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417) + linearFormats
private val printedDigits = Regex("[0-9]{8,18}")

@Serializable
internal data class ImportedCode(val format: String, val value: String) {
    override fun toString() = "ImportedCode(format=" + format + ")"
}

@Serializable
internal data class ImportedTicket(
    val document: String,
    val page: Int,
    val fields: Map<TicketField, String>,
    val originalName: String,
    val code: ImportedCode?,
    val extras: Map<String, String> = emptyMap(),
) {
    val id get() = document + "-" + page
    override fun toString() = "ImportedTicket(page=" + page + ")"
}

internal data class PdfDraft(
    val file: File, val document: String, val originalName: String, val page: Int,
    val candidates: Map<TicketField, TicketFieldCandidate>, val codes: List<ImportedCode>, val hasText: Boolean,
    val extras: Map<String, String>,
    val replaces: ImportedTicket? = null,
)

internal enum class PdfStage { OPENING, TEXT, CODE, OCR }

internal data class PdfProgress(val stage: PdfStage, val page: Int = 0, val total: Int = 0)

internal enum class PdfProblem { UNREADABLE, PROTECTED, LIMIT, DUPLICATE, STORAGE }
internal class PdfImportFailure(val problem: PdfProblem) : IOException()

internal class PdfVaultStorage(private val context: Context, accountId: String) {
    private val vault = File(context.noBackupFilesDir, "aip004/" + sha256(accountId.toByteArray()))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<ImportedTicket> = vault.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        .mapNotNull { file ->
            runCatching { json.decodeFromString<ImportedTicket>(AtomicFile(file).readFully().decodeToString()) }
                .getOrNull()?.takeIf { fingerprint.matches(it.document) && source(it.document).isFile }
        }

    suspend fun read(uri: Uri, progress: (PdfProgress) -> Unit = {}): List<PdfDraft> {
        progress(PdfProgress(PdfStage.OPENING))
        val work = File(context.cacheDir, "aip004-incoming").apply { mkdirs() }
        val filename = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().take(180)
        val file = File.createTempFile("aip-", ".pdf", work)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > FILE_LIMIT) throw PdfImportFailure(PdfProblem.LIMIT)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw PdfImportFailure(PdfProblem.UNREADABLE)
            val signature = file.inputStream().use { input ->
                val bytes = ByteArray(1024)
                bytes.decodeToString(endIndex = input.read(bytes).coerceAtLeast(0))
            }
            if (!signature.contains("%PDF-")) throw PdfImportFailure(PdfProblem.UNREADABLE)
            val id = digest.digest().hex()
            val existing = list().filter { it.document == id }.map { it.page }.toSet()
            val drafts = recognize(file, id, filename, progress) { page -> page !in existing }
            if (drafts.isEmpty()) throw PdfImportFailure(PdfProblem.DUPLICATE)
            return drafts
        } catch (_: InvalidPasswordException) {
            file.delete()
            throw PdfImportFailure(PdfProblem.PROTECTED)
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }

    suspend fun reread(ticket: ImportedTicket, progress: (PdfProgress) -> Unit = {}): PdfDraft =
        recognize(source(ticket.document), ticket.document, ticket.originalName, progress, thorough = true) { it == ticket.page }
            .singleOrNull()?.copy(replaces = ticket) ?: throw PdfImportFailure(PdfProblem.UNREADABLE)

    suspend fun recheck(draft: PdfDraft, progress: (PdfProgress) -> Unit): PdfDraft {
        progress(PdfProgress(PdfStage.OPENING))
        return recognize(draft.file, draft.document, draft.originalName, progress, thorough = true) { it == draft.page }
            .singleOrNull()?.copy(replaces = draft.replaces) ?: throw PdfImportFailure(PdfProblem.UNREADABLE)
    }

    private suspend fun recognize(
        file: File, id: String, name: String, progress: (PdfProgress) -> Unit, thorough: Boolean = false,
        include: (Int) -> Boolean,
    ): List<PdfDraft> {
        val work = File(context.cacheDir, "aip004-incoming").apply { mkdirs() }
        PDFBoxResourceLoader.init(context)
        val ocr = PdfOcr(context)
        return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(work)).use { pdf ->
            if (pdf.isEncrypted) throw PdfImportFailure(PdfProblem.PROTECTED)
            if (pdf.numberOfPages !in 1..MAX_PAGES) throw PdfImportFailure(PdfProblem.LIMIT)
            val stripper = PdfWordStripper()
            withRenderer(file) { renderer ->
                if (renderer.pageCount != pdf.numberOfPages) throw PdfImportFailure(PdfProblem.UNREADABLE)
                val selectedPages = (0 until renderer.pageCount).filter(include)
                selectedPages.mapIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    fun stage(step: PdfStage) = progress(PdfProgress(step, index + 1, selectedPages.size))

                    stage(PdfStage.TEXT)
                    stripper.startPage = page + 1
                    stripper.endPage = page + 1
                    stripper.words.clear()
                    val text = stripper.getText(pdf).take(100_000)
                    val candidates = TicketFieldRecognition.extract(text).toMutableMap()
                    PositionedTicketRecognition.extract(stripper.words).forEach { (field, candidate) ->
                        if (candidates[field]?.evidence != FieldEvidence.LABEL) candidates[field] = candidate
                    }

                    stage(PdfStage.CODE)
                    val codes = recognizeCodes(renderer, page, stripper.words, thorough)
                    currentCoroutineContext().ensureActive()

                    val missingSeats = listOf(TicketField.SECTOR, TicketField.ROW, TicketField.SEAT)
                        .count { candidates[it]?.value.isNullOrBlank() } >= 2
                    val seatRegion = if (missingSeats) seatLabelRegion(stripper.words) else null
                    if (text.isBlank() || seatRegion != null) {
                        stage(PdfStage.OCR)
                        val raster = render(renderer, page, 1800)
                        val words = try { ocr.words(raster, seatRegion?.toPixels(raster)) } finally { raster.recycle() }
                        val ocrText = words.sortedWith(compareBy({ (it.baseline * 145).toInt() }, { it.left }))
                            .groupBy { (it.baseline * 145).toInt() }.values.joinToString("\n") { line ->
                                line.joinToString(" ") { it.text }
                            }
                        val recognized = TicketFieldRecognition.extract(ocrText) + PositionedTicketRecognition.extract(words)
                        recognized.forEach { (field, candidate) ->
                            if (field !in candidates && candidate.value.isNotBlank())
                                candidates[field] = candidate.copy(evidence = FieldEvidence.OCR)
                        }
                    }
                    PdfDraft(file, id, name, page, candidates, codes, text.isNotBlank(),
                        TicketFieldRecognition.extractExtras(text))
                }
            }
        }
    }

    fun save(draft: PdfDraft, fields: Map<TicketField, String>, code: ImportedCode?,
        extras: Map<String, String> = draft.extras): ImportedTicket {
        val ticket = ImportedTicket(draft.document, draft.page, fields, draft.originalName, code, extras)
        val rows = list()
        val exists = rows.any { it.id == ticket.id }
        if (exists && draft.replaces == null) throw PdfImportFailure(PdfProblem.DUPLICATE)
        if (!exists && draft.replaces != null) throw PdfImportFailure(PdfProblem.STORAGE)
        val target = source(draft.document)
        if (!exists && rows.size >= MAX_CARDS || !target.isFile && vault.listFiles().orEmpty().sumOf { it.length() } + draft.file.length() > VAULT_LIMIT)
            throw PdfImportFailure(PdfProblem.LIMIT)
        check(vault.isDirectory || vault.mkdirs())
        val copied = !target.isFile
        if (copied) atomic(target) { out -> draft.file.inputStream().use { it.copyTo(out) } }
        try {
            atomic(meta(ticket)) { it.write(json.encodeToString(ticket).encodeToByteArray()) }
        } catch (exception: Exception) {
            if (copied) AtomicFile(target).delete()
            throw exception
        }
        return ticket
    }

    fun delete(ticket: ImportedTicket) {
        AtomicFile(meta(ticket)).delete()
        if (meta(ticket).exists()) throw PdfImportFailure(PdfProblem.STORAGE)
        if (list().none { it.document == ticket.document }) AtomicFile(source(ticket.document)).delete()
    }

    fun page(ticket: ImportedTicket, index: Int): Pair<Bitmap, Int> = withRenderer(source(ticket.document)) {
        val page = index.coerceIn(0, it.pageCount - 1)
        render(it, page) to it.pageCount
    }

    fun preview(draft: PdfDraft): Bitmap = withRenderer(draft.file) { render(it, draft.page) }

    private fun source(id: String): File {
        require(fingerprint.matches(id))
        return File(vault, id + ".pdf")
    }

    private fun meta(ticket: ImportedTicket) = File(vault, ticket.id + ".json")

    private inline fun atomic(file: File, block: (java.io.FileOutputStream) -> Unit) {
        val target = AtomicFile(file)
        val output = target.startWrite()
        try {
            block(output)
            target.finishWrite(output)
        } catch (exception: Exception) {
            target.failWrite(output)
            throw exception
        }
    }
}

private inline fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use(block)
    }

private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
    try { block(this) } finally { recycle() }

private data class PageRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toPixels(page: Bitmap) = Rect(
        (left * page.width).toInt(), (top * page.height).toInt(),
        (right * page.width).toInt().coerceIn(1, page.width),
        (bottom * page.height).toInt().coerceIn(1, page.height),
    )
}

/** A vertical group of isolated numbers may have labels painted alongside the text layer. */
private fun seatLabelRegion(words: List<TicketWord>): PageRegion? {
    val numbers = words.filter { word ->
        word.text.matches(Regex("[0-9]{1,4}")) && word.left in 0f..0.95f && word.baseline in 0f..0.95f
    }.sortedBy { it.baseline }
    val groups = numbers.mapNotNull { first ->
        numbers.filter { word ->
            kotlin.math.abs(word.left - first.left) <= 0.035f && word.baseline in first.baseline..(first.baseline + 0.13f)
        }.distinctBy { (it.baseline * 128).toInt() }
            .takeIf { it.size in 3..5 }
    }
    val group = groups.maxByOrNull { it.size } ?: return null
    return PageRegion(
        (group.minOf { it.left } - 0.14f).coerceAtLeast(0f),
        (group.first().baseline - 0.028f).coerceAtLeast(0f),
        (group.maxOf { it.right } + 0.22f).coerceAtMost(1f),
        (group.last().baseline + 0.028f).coerceAtMost(1f),
    )
}

private fun render(renderer: PdfRenderer, index: Int, longEdge: Int = 1600,
    region: PageRegion? = null): Bitmap = renderer.openPage(index).use { page ->
    val scale = longEdge.toFloat() / maxOf(page.width, page.height)
    val bounds = region ?: PageRegion(0f, 0f, 1f, 1f)
    val width = (page.width * scale * (bounds.right - bounds.left)).toInt().coerceAtLeast(1)
    val height = (page.height * scale * (bounds.bottom - bounds.top)).toInt().coerceAtLeast(1)
    val matrix = android.graphics.Matrix().apply {
        setScale(scale, scale)
        postTranslate(-page.width * scale * bounds.left, -page.height * scale * bounds.top)
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(Color.WHITE)
        page.render(it, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }
}

private fun luminance(bitmap: Bitmap): RGBLuminanceSource {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
}

private suspend fun recognizeCodes(renderer: PdfRenderer, page: Int, words: List<TicketWord>, thorough: Boolean): List<ImportedCode> {
    val first = render(renderer, page).useBitmap(::decodeCodes)
    val linearNames = linearFormats.map { it.name }.toSet()
    val printed = words.filter { it.text.matches(printedDigits) && it.left in 0f..1f && it.baseline in 0f..1f }
        .distinctBy { Triple(it.text, (it.left * 100).toInt(), (it.baseline * 100).toInt()) }.take(12)
    val printedValues = printed.map { it.text }.toSet()
    val matching = first.filter { it.format in linearNames && it.value in printedValues }.toMutableList()
    if (matching.isEmpty()) {
        for (word in printed) {
            currentCoroutineContext().ensureActive()
            val region = PageRegion(
                (word.left - 0.14f).coerceAtLeast(0f),
                (word.baseline - 0.17f).coerceAtLeast(0f),
                (word.right + 0.18f).coerceAtMost(1f),
                (word.baseline + 0.07f).coerceAtMost(1f),
            )
            val match = render(renderer, page, 3200, region).useBitmap { tile ->
                val preferred = if (word.text.length == 13) BarcodeFormat.EAN_13 else BarcodeFormat.CODE_128
                decodeSingleCode(tile, listOf(preferred)) { it == word.text }
                    ?: decodeSingleCode(tile, linearFormats - preferred) { it == word.text }
            }
            if (match != null) matching += match
        }
    }
    if (matching.isNotEmpty()) return (first.filterNot { it.format in linearNames } + matching).distinct()

    // A QR can win a whole-page search even if one-dimensional bars are also present.
    val linear = if ((thorough || printed.isNotEmpty() || first.isEmpty()) && first.none { it.format in linearNames }) {
        render(renderer, page, if (thorough) 3200 else 2600).useBitmap { decodeSingleCode(it, linearFormats) }
    } else null
    if (first.isNotEmpty() || linear != null) return (first + listOfNotNull(linear)).distinct()
    return listOfNotNull(render(renderer, page, 2600).useBitmap { decodeSingleCode(it, formats) })
}

private fun decodeSingleCode(bitmap: Bitmap, supported: List<BarcodeFormat>, accepts: (String) -> Boolean = { true }): ImportedCode? {
    val source = luminance(bitmap)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.POSSIBLE_FORMATS to supported)
    val result = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints) }.getOrNull()
        ?.takeIf { accepts(it.text) }
        ?: runCatching { MultiFormatReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints) }.getOrNull()
            ?.takeIf { accepts(it.text) }
    return result?.takeIf { it.text.isNotBlank() && it.text.length <= 4096 }
        ?.let { ImportedCode(it.barcodeFormat.name, it.text) }
}

private fun decodeCodes(bitmap: Bitmap): List<ImportedCode> {
    val source = luminance(bitmap)
    val image = BinaryBitmap(HybridBinarizer(source))
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.ALSO_INVERTED to true, DecodeHintType.POSSIBLE_FORMATS to formats)
    val qr = runCatching { QRCodeMultiReader().decodeMultiple(image, hints).toList() }.getOrDefault(emptyList())
    val other = runCatching { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(image, hints).toList() }.getOrDefault(emptyList())
    val fallback = if (qr.isEmpty() && other.isEmpty()) {
        runCatching { MultiFormatReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints) }.getOrNull()
    } else null
    return (qr + other + listOfNotNull(fallback)).filter { it.text.isNotBlank() && it.text.length <= 4096 }.map { ImportedCode(it.barcodeFormat.name, it.text) }.distinct()
}

/** QR, Aztec and Data Matrix are not transcoded to different symbologies. Preserve original PDF as fallback. */
internal fun codeBitmap(code: ImportedCode): Bitmap? = runCatching {
    val format = BarcodeFormat.valueOf(code.format)
    val linear = format in listOf(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.ITF)
    val matrix = MultiFormatWriter().encode(code.value, format, 900, if (linear) 280 else 900,
        mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 4))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
