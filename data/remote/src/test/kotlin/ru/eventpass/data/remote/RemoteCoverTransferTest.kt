package ru.eventpass.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.core.domain.event.*

class RemoteCoverTransferTest {
    @Test fun lateUploadAfterLogoutDoesNotAttachAndReleasesManagedFile() = interruptedUpload(false)
    @Test fun cancelledUploadReleasesManagedFileWithoutRetry() = interruptedUpload(true)
    private fun interruptedUpload(cancel: Boolean) = runTest {
        MockWebServer().use { server -> server.start()
            val api=AuthHttp(server.url("/api/").toString()); val auth=RemoteAuthRepository(api,Store()); login(server,auth)
            var releases=0
            val managed=object:CoverAssetReader {
                override suspend fun read(cover:EventCover)="data".toByteArray()
                override suspend fun release(cover:EventCover) { releases++ }
            }
            val body="""{"assetId":"server-asset","url":"/api/assets/event-covers/server-asset","mimeType":"image/png","byteCount":4,"width":1,"height":1}"""
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","application/json").body(body).bodyDelay(400,TimeUnit.MILLISECONDS).build())
            val uploading=async(Dispatchers.IO) { RemoteCoverTransfer(api,auth,managed).importCover("opaque",local) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            if(cancel) uploading.cancelAndJoin()
            else { server.enqueue(json("{}")); auth.signOut(); assertEquals(CoverImportResult.Unavailable,uploading.await()) }
            assertEquals(1,releases)
        }
    }
    private class Store:SessionTokenStore {
        var token:AuthSecret?=null
        override suspend fun read()=token
        override suspend fun write(token:AuthSecret){this.token=token}
        override suspend fun clear(){token=null}
    }
    private val cover=EventCover("local-asset","image/png",4)
    private val local=object:CoverImporter {override suspend fun importCover(source:String)=CoverImportResult.Imported(cover)}
    private val reader=object:CoverAssetReader {override suspend fun read(cover:EventCover)="data".toByteArray()}
    private fun json(body:String)=MockResponse.Builder().addHeader("Content-Type","application/json").body(body).build()
    private suspend fun login(server:MockWebServer,auth:RemoteAuthRepository){
        server.enqueue(json("""{"token":"bearer","user":{"id":"actor","publicId":"123456","fullName":"Alex","email":"alex@example.com","phone":null,"organization":null,"emailVerified":false,"notificationsEnabled":true}}"""))
        assertTrue(auth.signIn("alex@example.com",AuthSecret("password")) is AuthResult.Success)
        withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)}
    }
    @Test fun uploadUsesMultipartFileAndServerIdWhileIgnoringForeignReturnedUrl()=runTest {
        MockWebServer().use {server->server.start();val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store());login(server,auth)
            server.enqueue(json("""{"assetId":"server-asset","url":"https://foreign.invalid/secret","mimeType":"image/png","byteCount":4,"width":1,"height":1}"""))
            val transfer=RemoteCoverTransfer(api,auth,reader)
            val result=transfer.importCover("opaque",local) as CoverImportResult.Imported
            assertEquals("server-asset",result.cover.remoteAssetId);assertEquals("local-asset",result.cover.assetId)
            val request=withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)}!!
            assertEquals("/api/assets/event-covers",request.url.encodedPath);assertEquals("Bearer bearer",request.headers["Authorization"])
            assertNull(request.headers["Idempotency-Key"]);assertTrue(request.body!!.utf8().contains("name=\"file\""))
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","image/png").body("data").build())
            assertArrayEquals("data".toByteArray(),transfer.read(result.cover.remoteAssetId!!))
            val get=withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)}!!
            assertEquals("/api/assets/event-covers/server-asset",get.url.encodedPath)
        }
    }
    @Test fun wrongMimeAndOversizedDownloadFailWithoutUnboundedReadOrRedirect()=runTest {
        MockWebServer().use {server->server.start();val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store())
            val transfer=RemoteCoverTransfer(api,auth,reader)
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","text/html").body("bad").build())
            assertNull(transfer.read("asset"))
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location","https://foreign.invalid/").build())
            assertNull(transfer.read("asset"))
            server.enqueue(MockResponse.Builder().addHeader("Content-Type","image/png").body("x".repeat(5_242_881)).build())
            assertNull(transfer.read("asset"));assertEquals(3,server.requestCount)
        }
    }
    @Test fun missingManagedAssetNeverUploadsAndGuestCannotUpload()=runTest {
        MockWebServer().use {server->server.start();val api=AuthHttp(server.url("/api/").toString());val auth=RemoteAuthRepository(api,Store())
            val transfer=RemoteCoverTransfer(api,auth,object:CoverAssetReader{override suspend fun read(cover:EventCover):ByteArray?=null})
            assertEquals(CoverImportResult.Unavailable,transfer.importCover("opaque",local));assertEquals(0,server.requestCount)
            login(server,auth);assertEquals(CoverImportResult.Unavailable,transfer.importCover("opaque",local));assertEquals(1,server.requestCount)
        }
    }
}
