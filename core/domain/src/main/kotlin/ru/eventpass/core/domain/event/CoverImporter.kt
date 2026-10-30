package ru.eventpass.core.domain.event

interface CoverImporter {
    suspend fun importCover(source: String): CoverImportResult
}
sealed interface CoverImportResult {
    data class Imported(val cover: EventCover) : CoverImportResult
    data object InvalidImage : CoverImportResult
    data object TooLarge : CoverImportResult
    data object Unavailable : CoverImportResult
}
