package ru.eventpass.core.platform

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.eventpass.core.domain.documents.*

class AndroidDocumentFiles(private val context: Context) : DocumentFiles {
    private val directory=File(context.cacheDir,"eventpass-documents")
    private val initialization=Mutex(); private var initialized=false
    override suspend fun cleanup() = withContext(Dispatchers.IO) { initialization.withLock {
        if(!initialized) { directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }; initialized=true }
    } }
    override suspend fun save(snapshot: DocumentSnapshot, destination: String, authorize: suspend () -> DocumentError?): DocumentError? = withContext(Dispatchers.IO) {
        cleanup(); val uri=Uri.parse(destination)
        if(uri.scheme != "content") return@withContext DocumentError.PROVIDER_UNAVAILABLE
        val contextJob=currentCoroutineContext(); var temporary: File?=null
        try {
            authorize()?.let { return@withContext it }; contextJob.ensureActive()
            if(!directory.exists() && !directory.mkdirs()) return@withContext DocumentError.WRITE_FAILED
            val file=File(directory,UUID.randomUUID().toString()+".tmp"); temporary=file
            file.outputStream().use { output -> when(snapshot) {
                is DocumentSnapshot.Ticket -> TicketPdfWriter.write(snapshot,output) { contextJob.ensureActive() }
                is DocumentSnapshot.Attendance -> AttendanceXlsxWriter.write(snapshot,output) { contextJob.ensureActive() }
            } }
            authorize()?.let { return@withContext it }; contextJob.ensureActive()
            val output=context.contentResolver.openOutputStream(uri,"wt") ?: return@withContext DocumentError.PROVIDER_UNAVAILABLE
            DocumentStreams.copy({file.inputStream()},output,authorize)?.let { return@withContext it }
            contextJob.ensureActive(); authorize()
        } catch(e: CancellationException) { throw e }
        catch(_: SecurityException) { DocumentError.PROVIDER_UNAVAILABLE }
        catch(_: IOException) { DocumentError.WRITE_FAILED }
        catch(_: IllegalArgumentException) { DocumentError.WRITE_FAILED }
        catch(_: com.google.zxing.WriterException) { DocumentError.WRITE_FAILED }
        finally { temporary?.delete() }
    }
}
