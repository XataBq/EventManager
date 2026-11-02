package ru.eventpass.core.platform

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.eventpass.core.domain.documents.DocumentError

internal object DocumentStreams {
    suspend fun copy(source: () -> InputStream, output: OutputStream, authorize: suspend () -> DocumentError?): DocumentError? =
        output.use { sink -> source().use { input ->
            // The provider was already opened; authorization must run again before the first byte.
            val buffer=ByteArray(32*1024)
            while(true) {
                currentCoroutineContext().ensureActive(); authorize()?.let { return it }
                val count=input.read(buffer); if(count<0) break; sink.write(buffer,0,count)
            }
            sink.flush(); null
        } }
}
