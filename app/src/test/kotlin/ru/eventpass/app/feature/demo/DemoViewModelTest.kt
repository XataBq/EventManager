package ru.eventpass.app.feature.demo

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.eventpass.core.domain.DemoRecord
import ru.eventpass.core.domain.DemoRepository

@OptIn(ExperimentalCoroutinesApi::class)
class DemoViewModelTest {
    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun restoredCountAndRepositoryDataComposeIntoState() = runTest {
        val handle = SavedStateHandle(mapOf("count" to 7))
        val repository = object : DemoRepository {
            override fun observeRecord(id: String) = flowOf(DemoRecord(id, "test-seed"))
        }
        val viewModel = DemoViewModel(repository, handle, "demo")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        assertEquals(DemoUiState.Content("test-seed", 7), viewModel.uiState.value)
        viewModel.increment()
        assertEquals(DemoUiState.Content("test-seed", 8), viewModel.uiState.value)
        assertEquals(8, handle.get<Int>("count"))
    }

    @Test fun missingRecordIsExplicitAndNewEntryStartsEmpty() = runTest {
        val repository = object : DemoRepository {
            override fun observeRecord(id: String) = flowOf<DemoRecord?>(null)
        }
        val viewModel = DemoViewModel(repository, SavedStateHandle(), "missing")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        assertEquals(DemoUiState.NotFound, viewModel.uiState.value)
    }
}
