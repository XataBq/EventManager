package ru.eventpass.app.feature.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.eventpass.core.domain.checkin.*
import ru.eventpass.core.domain.event.EventId

@HiltViewModel
class ScannerViewModel @Inject constructor(private val repository: CheckInRepository) : ViewModel() {
    private val controller = ScannerController(repository, viewModelScope)
    private var eventId: EventId? = null
    private var read:kotlinx.coroutines.Job?=null
    val state = controller.state
    private val input = MutableStateFlow(""); val manualInput = input.asStateFlow()
    private val failed = MutableStateFlow(false); val cameraFailed = failed.asStateFlow()
    fun bind(id: String) { if(eventId?.value!=id) {read?.cancel();input.value=""};eventId = EventId(id); controller.bind(EventId(id)) }
    fun edit(value: String) { input.value = value.take(64) }
    fun submit() { val value = input.value; input.value = ""; controller.manual(value) }
    fun foreground(value: Boolean) { if (!value) {input.value = "";read?.cancel()}; controller.foreground(value) }
    fun permission(value: ScannerPermission) = controller.permission(value)
    fun camera() { failed.value = false; controller.requestCamera() }
    fun cameraFailure() { controller.foreground(false); controller.foreground(true); failed.value = true }
    fun frame(generation: Long, values: List<String>) = controller.frame(generation, values)
    fun next() = controller.next()
    fun retryScan() = controller.retry()
    fun back(onClose: () -> Unit) { if (state.value.phase == ScannerPhase.RESULT) controller.backFromResult() else onClose() }
    fun refresh() {
        val id=eventId ?: return
        if(read?.isActive==true) return
        read=viewModelScope.launch {repository.refreshAccess(id)}
    }
    override fun onCleared() { controller.close() }
}
