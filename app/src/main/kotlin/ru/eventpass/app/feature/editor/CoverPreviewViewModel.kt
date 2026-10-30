package ru.eventpass.app.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.eventpass.core.domain.event.EventCover
import ru.eventpass.core.platform.EventCoverPreview

@HiltViewModel
class CoverPreviewViewModel @Inject constructor(private val preview: EventCoverPreview) : ViewModel() {
    private val mutable = MutableStateFlow<ImageBitmap?>(null)
    val image = mutable.asStateFlow()
    private var cover: EventCover? = null
    private var job: Job? = null
    fun bind(value: EventCover?) {
        if (cover == value) return
        cover = value; job?.cancel(); mutable.value = null
        if (value != null) job = viewModelScope.launch {
            val bitmap = preview.preview(value)
            if (cover == value) mutable.value = bitmap?.asImageBitmap()
        }
    }
}
