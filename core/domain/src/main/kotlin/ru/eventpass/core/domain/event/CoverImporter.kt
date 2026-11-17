package ru.eventpass.core.domain.event

interface CoverImporter {
    suspend fun importCover(source: String): CoverImportResult
}
/** Data adapters may read only managed local assets, never arbitrary UI file paths. */
interface CoverAssetReader {
    suspend fun read(cover: EventCover): ByteArray?
    suspend fun release(cover: EventCover) {}
}
sealed interface CoverImportResult {
    data class Imported(val cover: EventCover) : CoverImportResult
    data object InvalidImage : CoverImportResult
    data object TooLarge : CoverImportResult
    data object Unavailable : CoverImportResult
}
