package ru.eventpass.app.feature.pdfvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import ru.eventpass.core.domain.importedticket.TicketWord
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Optional offline fallback. Model files carry no user data and can be reused for future imports. */
internal class PdfOcr(private val context: Context) {
    suspend fun words(page: Bitmap, region: Rect?): List<TicketWord> {
        val area = region ?: Rect(0, 0, page.width, page.height)
        val pageWidth = page.width
        val pageHeight = page.height
        val limit = if (startedBefore.getAndSet(true)) 5_000L else 8_000L
        return withTimeoutOrNull(limit) {
            suspendCancellableCoroutine { callback ->
                if (!slot.tryAcquire()) {
                    callback.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                // The worker owns a separate image, because the caller can time out and recycle the page.
                try {
                    val crop = Bitmap.createBitmap(page, area.left, area.top, area.width(), area.height())
                    val owned = if (crop === page) checkNotNull(page.copy(Bitmap.Config.ARGB_8888, false)) else crop
                    worker.execute {
                        var engine: TessBaseAPI? = null
                        val result = try {
                            engine = if (callback.isActive) start() else null
                            if (engine != null && callback.isActive) recognize(engine, owned, area, pageWidth, pageHeight)
                            else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        } catch (_: LinkageError) {
                            emptyList()
                        } finally {
                            try { engine?.end() } finally { owned.recycle(); slot.release() }
                        }
                        if (callback.isActive) callback.resume(result)
                    }
                } catch (_: RuntimeException) {
                    slot.release()
                    callback.resume(emptyList())
                }
            }
        }.orEmpty()
    }

    private fun recognize(api: TessBaseAPI, bitmap: Bitmap, area: Rect, width: Int, height: Int): List<TicketWord> {
        return try {
            api.setImage(bitmap)
            api.getUTF8Text() // Runs recognition before requesting word positions.
            val iterator = api.getResultIterator() ?: return emptyList()
            try {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                buildList {
                    iterator.begin()
                    do {
                        val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                        val box = iterator.getBoundingRect(level)
                        if (text.isNotBlank() && box != null && iterator.confidence(level) >= 55f) {
                            add(TicketWord(text, (box.left + area.left).toFloat() / width, (box.right + area.left).toFloat() / width,
                                (box.bottom + area.top).toFloat() / height, box.height().toFloat() / height))
                        }
                    } while (size < 8000 && iterator.next(level))
                }
            } finally { iterator.delete() }
        } catch (_: RuntimeException) {
            emptyList()
        } finally { api.clear() }
    }

    private fun start(): TessBaseAPI? {
        val root = File(context.noBackupFilesDir, "aip004-model-3.04.00")
        val modelDir = File(root, "tessdata")
        synchronized(modelLock) {
            check(modelDir.isDirectory || modelDir.mkdirs())
            for (language in listOf("rus", "eng")) {
                val name = "$language.traineddata"
                val target = AtomicFile(File(modelDir, name))
                if (target.baseFile.length() > 1_000_000L) continue
                val stream = target.startWrite()
                try {
                    context.assets.open("aip004/tessdata/$name").use { it.copyTo(stream) }
                    target.finishWrite(stream)
                } catch (error: Exception) {
                    target.failWrite(stream)
                    throw error
                }
            }
        }
        val api = TessBaseAPI()
        if (!api.init(root.absolutePath, "rus+eng", TessBaseAPI.OEM_TESSERACT_ONLY)) {
            api.end()
            return null
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        return api
    }

    private companion object {
        val modelLock = Any()
        val startedBefore = AtomicBoolean(false)
        val slot = Semaphore(1)
        val worker = Executors.newSingleThreadExecutor { work -> Thread(work, "ticket-ocr").apply { isDaemon = true } }
    }
}
