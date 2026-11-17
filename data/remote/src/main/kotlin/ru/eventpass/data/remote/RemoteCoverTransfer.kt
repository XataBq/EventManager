package ru.eventpass.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.auth.AuthError

/** Upload has no retry or invented idempotency. A lost response can leave an orphan server asset. */
class RemoteCoverTransfer(private val api: AuthHttp, private val auth: RemoteAuthRepository, private val reader: CoverAssetReader) {
    suspend fun importCover(source: String, local: CoverImporter): CoverImportResult {
        val c = auth.capturePublicRead()
        if(c.token == null) return CoverImportResult.Unavailable
        var managed: EventCover? = null
        return try {
            val imported = local.importCover(source)
            if(imported !is CoverImportResult.Imported) return imported
            managed = imported.cover
            val bytes = reader.read(imported.cover) ?: return CoverImportResult.Unavailable
            if(!auth.isCurrentPublicRead(c)) return CoverImportResult.Unavailable
            val dto = api.json.decodeFromString<UploadedCover>(api.uploadCover(bytes,imported.cover.mimeType,c.token))
            currentCoroutineContext().ensureActive()
            if(!auth.isCurrentPublicRead(c)) return CoverImportResult.Unavailable
            require(dto.assetId.isNotBlank() && dto.assetId.length <= 300)
            require(dto.byteCount == bytes.size.toLong() && dto.mimeType == imported.cover.mimeType)
            require(dto.width in 1..32768 && dto.height in 1..32768)
            CoverImportResult.Imported(imported.cover.copy(remoteAssetId=dto.assetId))
        } catch(e: CancellationException) { throw e }
        catch(e: RemoteFailure) { if(e.error == AuthError.Unauthorized) auth.rejectUnauthorized(c); CoverImportResult.Unavailable }
        catch(e: Exception) { CoverImportResult.Unavailable }
        finally { managed?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { reader.release(it) } } }
    }
    suspend fun read(assetId: String): ByteArray? {
        val c = auth.capturePublicRead()
        return try {
            val bytes = api.readCover(assetId,c.token)
            currentCoroutineContext().ensureActive()
            bytes.takeIf { auth.isCurrentPublicRead(c) }
        } catch(e: CancellationException) { throw e }
        catch(e: RemoteFailure) { if(e.error == AuthError.Unauthorized) auth.rejectUnauthorized(c); null }
        catch(e: Exception) { null }
    }
}
@Serializable private data class UploadedCover(val assetId: String,val url: String,val mimeType: String,val byteCount: Long,val width: Int,val height: Int)
