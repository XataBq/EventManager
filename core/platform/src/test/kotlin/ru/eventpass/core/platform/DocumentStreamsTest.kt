package ru.eventpass.core.platform

import java.io.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.documents.DocumentError

class DocumentStreamsTest {
    private class Sink(private val failClose: Boolean=false) : ByteArrayOutputStream() {
        var closed=false
        override fun close() {closed=true;if(failClose) throw IOException("close fixture");super.close()}
    }
    @Test fun writerClosesOnSuccessDenialAndCancellationAndNeverSucceedsOnCloseFailure()=runBlocking {
        val success=Sink(); assertNull(DocumentStreams.copy({byteArrayOf(1,2).inputStream()},success){null});assertTrue(success.closed);assertArrayEquals(byteArrayOf(1,2),success.toByteArray())
        val denied=Sink();assertEquals(DocumentError.SESSION_CHANGED,DocumentStreams.copy({byteArrayOf(1).inputStream()},denied){DocumentError.SESSION_CHANGED})
        assertTrue(denied.closed);assertEquals(0,denied.size())
        val cancelled=Sink();try {DocumentStreams.copy({byteArrayOf(1).inputStream()},cancelled){throw CancellationException("fixture")};fail()} catch(_: CancellationException) {assertTrue(cancelled.closed)}
        val broken=Sink(true);try {DocumentStreams.copy({byteArrayOf(1).inputStream()},broken){null};fail()} catch(_: IOException) {assertTrue(broken.closed)}
        val unreadable=Sink();try {DocumentStreams.copy({throw IOException("source fixture")},unreadable){null};fail()} catch(_: IOException) {assertTrue(unreadable.closed)}
    }
    @Test fun changedSessionDuringTransferStopsBeforeNextChunk()=runBlocking {
        val sink=Sink();var calls=0
        assertEquals(DocumentError.SESSION_CHANGED,DocumentStreams.copy({ByteArray(100_000).inputStream()},sink){if(++calls>1) DocumentError.SESSION_CHANGED else null})
        assertEquals(32768,sink.size());assertTrue(sink.closed)
    }
}
