package ru.eventpass.core.domain

import kotlinx.coroutines.flow.Flow

/** Technical spike only. Product repositories are introduced with their features. */
data class DemoRecord(val id: String, val seed: String)

interface DemoRepository {
    fun observeRecord(id: String): Flow<DemoRecord?>
}
