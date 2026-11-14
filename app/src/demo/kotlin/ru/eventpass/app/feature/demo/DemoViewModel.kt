package ru.eventpass.app.feature.demo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.eventpass.core.domain.DemoRepository

sealed interface DemoUiState {
    data object Loading : DemoUiState
    data object NotFound : DemoUiState
    data class Content(val seed: String, val count: Int) : DemoUiState
}

@HiltViewModel(assistedFactory = DemoViewModel.Factory::class)
class DemoViewModel @AssistedInject constructor(
    repository: DemoRepository,
    private val savedStateHandle: SavedStateHandle,
    @Assisted recordId: String,
) : ViewModel() {
    val uiState: StateFlow<DemoUiState> = combine(
        repository.observeRecord(recordId),
        savedStateHandle.getStateFlow("count", 0),
    ) { record, count ->
        if (record == null) DemoUiState.NotFound else DemoUiState.Content(record.seed, count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DemoUiState.Loading)

    fun increment() {
        val current = savedStateHandle.get<Int>("count") ?: 0
        if (current < Int.MAX_VALUE) savedStateHandle["count"] = current + 1
    }

    @AssistedFactory
    interface Factory {
        fun create(recordId: String): DemoViewModel
    }
}
