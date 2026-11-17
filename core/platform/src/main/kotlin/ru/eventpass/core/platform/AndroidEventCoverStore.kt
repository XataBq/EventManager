package ru.eventpass.core.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.event.*

/** Copies bounded content to private durable storage; no dependency on temporary URI grants. */
interface EventCoverPreview {
    suspend fun preview(cover: EventCover): Bitmap?
    suspend fun previewRemote(assetId: String): Bitmap? = null
}
class AndroidEventCoverStore(context: Context) : CoverImporter, EventCoverPreview, CoverAssetReader {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.noBackupFilesDir, "event-covers")
    suspend fun cleanupUnreferenced(retained: Set<String>) = withContext(Dispatchers.IO) {
        // Invoked once before the first import in a fresh process, before any unsaved asset exists.
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.(image|tmp)")) }
            ?.filter { it.name.removeSuffix(".image") !in retained }?.forEach { it.delete() }
        Unit
    }
    override suspend fun importCover(source: String): CoverImportResult = withContext(Dispatchers.IO) {
        if (Uri.parse(source).scheme != "content") return@withContext CoverImportResult.Unavailable
        directory.mkdirs()
        val temp = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            val input = resolver.openInputStream(Uri.parse(source)) ?: return@withContext CoverImportResult.Unavailable
            input.use { from -> temp.outputStream().use { to ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = from.read(buffer); if (n < 0) break
                    total += n
                    if (total > 5_242_880) return@withContext CoverImportResult.TooLarge
                    to.write(buffer, 0, n)
                }
            } }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, options)
            if (options.outMimeType !in setOf("image/png", "image/jpeg") || options.outWidth <= 0 || options.outHeight <= 0 ||
                options.outWidth > 32768 || options.outHeight > 32768) return@withContext CoverImportResult.InvalidImage
            val sample = sampleSize(options.outWidth, options.outHeight)
            val preview = BitmapFactory.decodeFile(temp.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext CoverImportResult.InvalidImage
            preview.recycle()
            val cover = EventCover(UUID.randomUUID().toString(), options.outMimeType, temp.length())
            currentCoroutineContext().ensureActive()
            if (!temp.renameTo(file(cover))) throw IOException("Cover commit failed")
            CoverImportResult.Imported(cover)
        } catch (_: IOException) { CoverImportResult.Unavailable }
        catch (_: SecurityException) { CoverImportResult.Unavailable }
        finally { temp.delete() }
    }
    override suspend fun preview(cover: EventCover): Bitmap? = withContext(Dispatchers.IO) {
        val source = file(cover)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(options.outWidth, options.outHeight) }) ?: return@withContext null
        val orientation = runCatching { android.media.ExifInterface(source.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        orient(bitmap,orientation)
    }
    suspend fun previewBytes(bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        if(bytes.size !in 1..5_242_880) return@withContext null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return@withContext null
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sampleSize(bounds.outWidth,bounds.outHeight) }) ?: return@withContext null
        val orientation=runCatching { bytes.inputStream().use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1) } }.getOrDefault(1)
        orient(bitmap,orientation)
    }
    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }
            8 -> setRotate(-90f)
        } }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    private fun file(cover: EventCover) = File(directory, "${cover.assetId}.image")
    override suspend fun release(cover: EventCover) = withContext(Dispatchers.IO) { file(cover).delete(); Unit }
    override suspend fun read(cover: EventCover): ByteArray? = withContext(Dispatchers.IO) {
        val source = file(cover)
        if(!source.isFile || source.length() != cover.byteCount || source.length() > 5_242_880) null
        else source.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(output.size() <= 5_242_880) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer,0,minOf(buffer.size,5_242_881-output.size()))
                if(count < 0) break
                output.write(buffer,0,count)
            }
            output.toByteArray().takeIf { it.size.toLong() == cover.byteCount }
        }
    }
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > 1024) sample *= 2
        return sample
    }
}
