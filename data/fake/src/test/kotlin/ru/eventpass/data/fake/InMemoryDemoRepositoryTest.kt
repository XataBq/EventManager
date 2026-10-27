package ru.eventpass.data.fake

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryDemoRepositoryTest {
    @Test fun exposesReproducibleSeedAndDoesNotInventMissingRecords() = runTest {
        val repository = InMemoryDemoRepository()
        assertEquals("t01-v1", repository.observeRecord("demo").first()?.seed)
        assertNull(repository.observeRecord("unknown").first())
    }
}
