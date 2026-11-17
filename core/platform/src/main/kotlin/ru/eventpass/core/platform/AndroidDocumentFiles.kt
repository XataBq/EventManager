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

class AndroidDocumentFiles(private val context: Context,
    private val localRenderer:((DocumentSnapshot,java.io.OutputStream,()->Unit)->Unit)?=null) : DocumentFiles {
    private val directory=File(context.cacheDir,"eventpass-documents")
    private val initialization=Mutex(); private var initialized=false
    private val epochs=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicLong>()
    private fun epoch(account:String)=epochs.getOrPut(account){java.util.concurrent.atomic.AtomicLong()}
    private fun prefix(account:String)="a"+java.security.MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString(""){"%02x".format(it)}+"_"
    override suspend fun purgePrivate(accountId:ru.eventpass.core.domain.event.UserId) = withContext(Dispatchers.IO) {
        epoch(accountId.value).incrementAndGet()
        initialization.withLock {directory.listFiles()?.asSequence()?.filter {it.isFile && it.name.startsWith(prefix(accountId.value)) && it.name.endsWith(".tmp")}?.take(256)?.forEach {it.delete()}}
        Unit
    }
    override suspend fun cleanup() = withContext(Dispatchers.IO) { initialization.withLock {
        if(!initialized) { directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }; initialized=true }
    } }
    override suspend fun save(snapshot: DocumentSnapshot, destination: String, authorize: suspend () -> DocumentError?): DocumentError? = withContext(Dispatchers.IO) {
        cleanup(); val uri=Uri.parse(destination)
        if(uri.scheme != "content") return@withContext DocumentError.PROVIDER_UNAVAILABLE
        val contextJob=currentCoroutineContext(); var temporary: File?=null
        val account=snapshot.stamp.accountId?.value.orEmpty();val expected=epoch(account).get()
        val guarded:suspend ()->DocumentError?={if(expected!=epoch(account).get()) DocumentError.SESSION_CHANGED else authorize()}
        try {
            guarded()?.let { return@withContext it }; contextJob.ensureActive()
            if(!directory.exists() && !directory.mkdirs()) return@withContext DocumentError.WRITE_FAILED
            val file=File(directory,prefix(account)+UUID.randomUUID().toString()+".tmp"); temporary=file
            file.outputStream().use { output -> when(snapshot) {
                is DocumentSnapshot.Remote -> snapshot.source.writeTo(output)
                else -> (localRenderer ?: return@withContext DocumentError.FEATURE_UNAVAILABLE).invoke(snapshot,output) {contextJob.ensureActive()}
            } }
            guarded()?.let { return@withContext it }; contextJob.ensureActive()
            val output=context.contentResolver.openOutputStream(uri,"wt") ?: return@withContext DocumentError.PROVIDER_UNAVAILABLE
            DocumentStreams.copy({file.inputStream()},output,guarded)?.let { return@withContext it }
            contextJob.ensureActive(); guarded()
        } catch(e: CancellationException) { throw e }
        catch(_: SecurityException) { DocumentError.PROVIDER_UNAVAILABLE }
        catch(e: DocumentFailure) { e.error }
        catch(_: IOException) { DocumentError.WRITE_FAILED }
        catch(_: IllegalArgumentException) { DocumentError.WRITE_FAILED }
        catch(_: com.google.zxing.WriterException) { DocumentError.WRITE_FAILED }
        finally { temporary?.delete() }
    }
}
