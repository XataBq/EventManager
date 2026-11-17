package ru.eventpass.data.remote

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.SessionState
import ru.eventpass.core.domain.documents.*
import ru.eventpass.core.domain.event.*

class RemoteDocumentSafetyTest {
    @Test fun jsonHtmlAndRedirectNeverReachUserOutput()=runBlocking {
        M09ProtocolFixture().use {f->
            val auth=f.auth();f.login(auth);val docs=RemoteTicketDocuments(f.api,auth)
            for(request in listOf(DocumentRequest.TicketPdf(TicketId("ticket")),DocumentRequest.AttendanceXlsx(EventId("server-event")))) {
                for(response in listOf(
                    MockResponse.Builder().code(200).addHeader("Content-Type","application/json").body("{}").build(),
                    MockResponse.Builder().code(200).addHeader("Content-Type","text/html").body("<html>error</html>").build(),
                    MockResponse.Builder().code(302).addHeader("Location","https://foreign.invalid/private").build())) {
                    f.intercept={r->if(r.url.encodedPath.endsWith("/pdf") || r.url.encodedPath.endsWith("/export")) response else null}
                    val prepared=docs.prepare(request) as DocumentResult.Ready
                    val output=ByteArrayOutputStream();val before=f.server.requestCount
                    val failure=runCatching {(prepared.snapshot as DocumentSnapshot.Remote).source.writeTo(output)}.exceptionOrNull()
                    assertTrue(failure is DocumentFailure);assertEquals(DocumentError.PROTOCOL,(failure as DocumentFailure).error)
                    assertEquals(0,output.size());assertEquals(before+1,f.server.requestCount)
                    assertTrue(auth.currentSession().state is SessionState.Authenticated)
                }
            }
        }
    }
    @Test fun lateDownloadForOldActorCannotWriteAndCurrent401RevokesSession()=runBlocking {
        M09ProtocolFixture().use {f->
            val auth=f.auth();f.login(auth);val docs=RemoteTicketDocuments(f.api,auth)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);f.cleanup+={release.countDown()}
            f.intercept={r->if(r.url.encodedPath.endsWith("/pdf")) {entered.countDown();check(release.await(15,TimeUnit.SECONDS));MockResponse.Builder().code(200).addHeader("Content-Type","application/pdf").body("%PDF-1.7\nsynthetic-fenced-response\n%%EOF").build()} else null}
            val prepared=docs.prepare(DocumentRequest.TicketPdf(TicketId("ticket"))) as DocumentResult.Ready
            val output=ByteArrayOutputStream();val download=async(Dispatchers.Default){runCatching {(prepared.snapshot as DocumentSnapshot.Remote).source.writeTo(output)}.exceptionOrNull()}
            assertTrue(entered.await(10,TimeUnit.SECONDS));f.user=M09ProtocolFixture.user("other","other@example.test");f.login(auth)
            release.countDown();assertEquals(DocumentError.SESSION_CHANGED,(download.await() as DocumentFailure).error);assertEquals(0,output.size())
            f.intercept={r->if(r.url.encodedPath.endsWith("/pdf")) M09ProtocolFixture.json("""{"code":"UNAUTHORIZED"}""",401) else null}
            val current=docs.prepare(DocumentRequest.TicketPdf(TicketId("ticket"))) as DocumentResult.Ready
            val failure=runCatching {(current.snapshot as DocumentSnapshot.Remote).source.writeTo(output)}.exceptionOrNull()
            assertEquals(DocumentError.AUTHENTICATION_REQUIRED,(failure as DocumentFailure).error)
            assertTrue(auth.currentSession().state is SessionState.Guest);assertEquals(0,output.size())
        }
    }
}
