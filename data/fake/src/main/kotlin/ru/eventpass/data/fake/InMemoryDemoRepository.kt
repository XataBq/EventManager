package ru.eventpass.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import ru.eventpass.core.domain.DemoRecord
import ru.eventpass.core.domain.DemoRepository

class InMemoryDemoRepository : DemoRepository {
    private val records = MutableStateFlow(listOf(DemoRecord("demo", "t01-v1")))

    override fun observeRecord(id: String): Flow<DemoRecord?> =
        records.map { items -> items.singleOrNull { it.id == id } }
}
