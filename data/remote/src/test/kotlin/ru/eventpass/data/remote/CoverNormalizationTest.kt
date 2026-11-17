package ru.eventpass.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.event.*

class CoverNormalizationTest {
    @Test fun normalizedRemoteSizeDoesNotInvalidateLocalSourceOrDuplicateUpload()=runBlocking {
        M09ProtocolFixture().use {f->
            val auth=f.auth();f.login(auth);f.coverByteCount=3
            var released=0
            val reader=object:CoverAssetReader {override suspend fun read(cover:EventCover)=byteArrayOf(1,2,3,4);override suspend fun release(cover:EventCover){released++}}
            val transfer=RemoteCoverTransfer(f.api,auth,reader);val local=EventCover("source","image/png",4)
            val pending=transfer.uploadPending(local,auth.capturePublicRead())
            assertEquals("asset-1",pending.remoteAssetId);assertEquals(4L,pending.byteCount);assertEquals(0,released)
            val online=transfer.importCover("fixture",object:CoverImporter {override suspend fun importCover(source:String)=CoverImportResult.Imported(local)}) as CoverImportResult.Imported
            assertEquals("asset-2",online.cover.remoteAssetId);assertEquals(4L,online.cover.byteCount);assertEquals(1,released)
            assertEquals(2,f.uploads.get())
        }
    }
    @Test fun impossibleServerDescriptorIsStillRejected()=runBlocking {
        M09ProtocolFixture().use {f->
            val auth=f.auth();f.login(auth);f.coverByteCount=0
            val local=EventCover("source","image/png",4)
            val reader=object:CoverAssetReader {override suspend fun read(cover:EventCover)=byteArrayOf(1,2,3,4);override suspend fun release(cover:EventCover){}}
            val result=RemoteCoverTransfer(f.api,auth,reader).importCover("fixture",object:CoverImporter {override suspend fun importCover(source:String)=CoverImportResult.Imported(local)})
            assertEquals(CoverImportResult.Unavailable,result)
        }
    }
}
